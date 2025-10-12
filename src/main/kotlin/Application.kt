package dev.babies.overmail

import com.overmail.core.ImapClient
import dev.babies.overmail.api.configureAuthentication
import dev.babies.overmail.api.configureRouting
import dev.babies.overmail.api.configureWebSockets
import dev.babies.overmail.api.webapp.realtime.folders.notifyFolderChange
import dev.babies.overmail.api.webapp.realtime.mails.notifyEmailChange
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.*
import dev.babies.overmail.di.configureKoin
import dev.babies.overmail.mail.daemon.EmailImporter
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.insert
import org.koin.ktor.ext.inject
import kotlin.time.Duration.Companion.minutes

val BASE_URL = System.getenv("BASE_URL") ?: "http://localhost"

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    runBlocking {
        Database.init()
    }
    configureKoin()
    configureSerialization()
    configureWebSockets()
    configureAuthentication()
    configureRouting()

    val emailImporter by inject<EmailImporter>()

    val imapConfigs = Database
        .query { ImapConfig.all().toList() }
        .map {
            ImapConfigClient(
                imapConfig = it,
                imapClient = ImapClient(
                    host = it.host,
                    port = it.port,
                    ssl = it.ssl,
                    username = it.username,
                    password = it.password,
                ),
            )
        }

    launch {
        while (isActive) {
            imapConfigs.forEach { config ->
                config.imapClient.use { client ->
                    client.testConnection()
                    val databaseMappings = client.getFolders()
                        .sortedBy { it.fullName }
                        .associateWith { imapFolder ->
                            Database.query {
                                ImapFolder
                                    .find { (ImapFolders.imapConfig eq config.imapConfig.id.value) and (ImapFolders.folderName eq imapFolder.name) }
                                    .firstOrNull { it.getPath() == imapFolder.path }
                                    ?.apply {
                                        if (this.folderName != imapFolder.name) this.folderName = imapFolder.name
                                    }
                                    ?: ImapFolder.new {
                                        this.imapConfig = config.imapConfig
                                        this.folderName = imapFolder.name
                                        this.parentFolder = ImapFolder
                                            .find { (ImapFolders.imapConfig eq config.imapConfig.id.value) }
                                            .firstOrNull { it.getPath() == imapFolder.path.dropLast(1) && it.getPath().isNotEmpty() }
                                }
                            }
                        }

                    databaseMappings.map { (imapFolder, databaseFolder) ->
                        launch {
                            val mails = imapFolder.getMails {
                                envelope = true
                                flags = true
                                uid = true
                                dumpMailOnError = true
                                getAll()
                            }

                            mails.forEach { email ->
                                val messageId = email.messageId.await()
                                val subject = email.subject.await()
                                val flags = email.flags.await()
                                val isRead = com.overmail.core.Email.Flag.Seen in flags
                                val sentAt = email.sentAt.await()
                                val folderUid = email.uid.await()
                                val from = email.from.await().ifEmpty { email.senders.await() }
                                val to = email.to.await()
                                val cc = email.cc.await()
                                val bcc = email.bcc.await()

                                var hasEmailExisted = false
                                var isReadChanged = false
                                var hasChanges = false

                                val dbEmail = Database.query {
                                    val senderIds = from.map { from -> getEmailUserOrCreate(from.name ?: from.address, from.address).id.value }
                                    val recipientIds =
                                        to.associate { to -> getEmailUserOrCreate(to.name ?: to.address, to.address).id.value to RecipientType.To } +
                                                cc.associate { cc -> getEmailUserOrCreate(cc.name ?: cc.address, cc.address).id.value to RecipientType.Cc } +
                                                bcc.associate { bcc -> getEmailUserOrCreate(bcc.name ?: bcc.address, bcc.address).id.value to RecipientType.Bcc }
                                    val email = Email
                                        .find { (Emails.imapConfig eq config.imapConfig.id) and (Emails.emailKey eq messageId) }
                                        .firstOrNull()
                                        ?.apply {
                                            hasEmailExisted = true
                                            if (this.isRead != (com.overmail.core.Email.Flag.Seen in flags)) {
                                                this.isRead = com.overmail.core.Email.Flag.Seen in flags
                                                isReadChanged = true
                                                hasChanges = true
                                            }
                                            if (this.folder.id != databaseFolder.id) {
                                                this.folder = databaseFolder
                                                hasChanges = true
                                            }
                                            if (this.folderUid != folderUid) {
                                                this.folderUid = folderUid
                                                hasChanges = true
                                            }
                                        }
                                        ?: Email.new {
                                            this.imapConfig = config.imapConfig
                                            this.emailKey = messageId
                                            this.subject = subject
                                            this.textBody = ""
                                            this.htmlBody = null
                                            this.isRead = isRead
                                            this.sentAt = sentAt
                                            this.sentBy
                                            this.emailKey = messageId
                                            this.folder = databaseFolder
                                            this.folderUid = folderUid
                                            this.rawSource = ExposedBlob(byteArrayOf())
                                            this.state = Email.State.Pending
                                            this.isRemoved = false
                                        }

                                    if (!hasEmailExisted) {
                                        senderIds
                                            .filter { email.sentBy.none { sender -> sender.id.value == it } }
                                            .forEach { senderId ->
                                                EmailSenders.insert {
                                                    it[this.email] = email.id
                                                    it[this.sender] = senderId
                                                }
                                            }

                                        recipientIds
                                            .filter { email.receivedBy.none { receiver -> receiver.recipient.id.value == it.key && receiver.type == it.value }}
                                            .forEach { (recipientId, type) ->
                                                EmailRecipients.insert {
                                                    it[this.email] = email.id
                                                    it[this.recipient] = recipientId
                                                    it[this.type] = type
                                                }
                                            }

                                    }

                                    return@query email
                                }
                                if (!isRead && !hasEmailExisted) notifyFolderChange(databaseFolder.id.value)
                                if (isReadChanged && hasEmailExisted) notifyFolderChange(databaseFolder.id.value)
                                if (hasChanges) notifyEmailChange(dbEmail.id.value)
                                if (!hasEmailExisted) emailImporter.import(dbEmail.id.value)
                            }
                        }
                    }.joinAll()
                }

                delay(5.minutes)
            }
        }
    }

    launch {
        emailImporter.start()
    }

    this.monitor.subscribe(ApplicationStopped) {
        emailImporter.stop()
    }
}

data class ImapConfigClient(
    val imapConfig: ImapConfig,
    val imapClient: ImapClient
)

private fun getEmailUserOrCreate(name: String, email: String): EmailUser {
    val name = name
        .trim()
        .takeIf { it.isNotEmpty() }
    return EmailUser.find { EmailUsers.email eq email }.firstOrNull() ?: EmailUser.new {
        this.displayName = name ?: email
        this.email = email
    }
}