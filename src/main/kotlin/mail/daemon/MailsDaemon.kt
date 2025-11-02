package dev.babies.overmail.mail.daemon

import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.*
import io.ktor.util.logging.*
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.io.IOException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Properties
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class MailsDaemon(
    private val dbFolder: ImapFolder,
    private val imapConfig: ImapConfig,
    private val serverFolderName: String,
    private val storeInstance: StoreInstance,
    private val contentImporterInstance: StoreInstance,
    private val coroutineScope: CoroutineScope,
) {

    private val logger = KtorSimpleLogger("${dbFolder.folderPath}/MailsDaemon@${imapConfig.host}")

    @Volatile
    private var isRunning = true
    private var syncJob: Job? = null
    private var importJob: Job? = null

    suspend fun upsertMessage(uid: Long) {
        try {
            storeInstance.withFolder(serverFolderName) { folder ->
                folder.getMessageByUID(uid)?.let { upsertMessage(it, uid) }
            }
        } catch (e: Exception) {
            logger.error("Error upserting message $uid: ${e.message}")
        }
    }

    val pendingMessages = Channel<ImportRequest>(capacity = Channel.UNLIMITED)

    init {
        syncJob = coroutineScope.launch {
            runSyncLoop()
        }

        importJob = coroutineScope.launch importWorker@{
            runImportLoop()
        }
    }

    private suspend fun runSyncLoop() {
        while (isRunning && coroutineScope.isActive) {
            try {
                logger.info("Updating folder")
                var uids = mutableListOf<Long>()
                storeInstance.withFolder(serverFolderName) { folder ->
                    val totalMessages = folder.messageCount
                    for (start in 1..totalMessages step 200) {
                        val end = minOf(start + 199, totalMessages)
                        val messages = folder.getMessages(start, end)
                        folder.fetch(messages, FetchProfile().apply {
                            add(UIDFolder.FetchProfileItem.FLAGS)
                            add(UIDFolder.FetchProfileItem.ENVELOPE)
                            add(UIDFolder.FetchProfileItem.UID)
                        })

                        logger.debug("Upserting ${messages.size} messages from $start to $end")
                        messages.forEach { message ->
                            val uid = folder.getUID(message)
                            uids.add(uid)
                            upsertMessage(message, uid)
                        }
                    }
                }

                Database.query {
                    Emails
                        .update(
                            where = {
                                (Emails.folder eq dbFolder.id.value) and (Emails.folderUid notInList uids) and (Emails.isRemoved eq false)
                            },
                            body = { it[Emails.isRemoved] = true }
                        )
                }

                delay((60..120).random().seconds)
            } catch (e: CancellationException) {
                logger.info("Sync loop cancelled")
                throw e
            } catch (e: Exception) {
                logger.error("Error in sync loop: ${e.message}")
                delay(30.seconds) // Wait before retry
            }
        }
    }

    private suspend fun runImportLoop() {
        Database.query {
            Emails
                .leftJoin(EmailContent)
                .select(Emails.id)
                .where {
                    EmailContent.id.isNull() and
                            (Emails.state eq Email.State.Pending) and
                            (Emails.folder eq dbFolder.id) and
                            (Emails.isRemoved eq false)
                }
                .map { ImportRequest(it[Emails.id].value, false) }
                .let { requests -> requests.forEach { pendingMessages.send(it) } }
        }

        for (request in pendingMessages) {
            if (!isRunning || !coroutineScope.isActive) break

            try {
                val email = Database.query { Email.findById(request.emailId) } ?: continue
                if (email.state != Email.State.Pending && !request.forceUpdate) continue

                logger.debug("Importing email ${email.id.value}")

                contentImporterInstance.withFolder(serverFolderName) { folder ->
                    val importId = Uuid.random()
                    var rawFile: File? = null
                    var textFile: File? = null
                    var htmlFile: File? = null
                    
                    try {
                        rawFile = File.createTempFile("overmail-email-content-$importId", ".eml")
                        val rawMessage = folder.getMessageByUID(email.folderUid)
                        run downloadMessageToDisk@{
                            val rawOriginalFile = File.createTempFile("overmail-email-content-$importId-raw", ".eml")
                            try {
                                if (rawMessage == null) {
                                    logger.error("Failed to import email ${email.id.value} (UID: ${email.folderUid}): Message not found on server")
                                    return@withFolder
                                }
                                rawOriginalFile.outputStream().use {
                                    rawMessage.writeTo(it)
                                }

                                // Replace utf-8 in Transfer Encoding as this causes problems with the JavaMail parser.
                                // This is a rare edge case; however, it has been observed by the very author of this in
                                // an e-mail from the state and university library of Dresden.
                                val rawInputStream = rawOriginalFile.inputStream()
                                rawInputStream.use { fis ->
                                    rawFile.outputStream().use { fos ->
                                        val reader = BufferedReader(InputStreamReader(fis))
                                        reader.useLines { lines ->
                                            lines.forEach { line ->
                                                if (line.contains("Content-Transfer-Encoding: utf-8", ignoreCase = true)) {
                                                    fos.write("Content-Transfer-Encoding: 8bit\r\n".toByteArray())
                                                } else {
                                                    fos.write((line + "\r\n").toByteArray())
                                                    fos.flush()
                                                }
                                            }
                                        }
                                    }
                                }
                            } finally {
                                rawOriginalFile.delete()
                            }
                        }


                        textFile = File.createTempFile("overmail-email-content-$importId", ".txt")
                        htmlFile = File.createTempFile("overmail-email-content-$importId", ".html")

                        val message = rawFile.inputStream().use {
                            MimeMessage(Session.getDefaultInstance(Properties()), it)
                        }
                        message.flags.clearUserFlags()
                        message.flags.clearSystemFlags()
                        rawMessage.flags.systemFlags.forEach { flag -> message.setFlag(flag, true) }
                        rawMessage.flags.userFlags.forEach { flag -> message.flags.add(flag) }
                        upsertMessage(message = message, uid = email.folderUid, forceUpdateOfUsers = request.forceUpdate)

                        textFile.outputStream().use { textOutputStream ->
                            htmlFile.outputStream().use { htmlOutputStream ->
                                fun handlePart(part: Any) {
                                    when (part) {
                                        is String -> textOutputStream.write(part.toByteArray())

                                        is Multipart -> {
                                            for (i in 0 until part.count) {
                                                handlePart(part.getBodyPart(i))
                                            }
                                        }

                                        is BodyPart -> {
                                            val disposition = part.disposition?.lowercase()
                                            if (disposition != null && disposition == "attachment") return

                                            val contentType = part.contentType.lowercase()
                                            val content = part.content

                                            when {
                                                contentType.contains("text/plain") && content is String -> {
                                                    textOutputStream.write(content.toByteArray())
                                                    textOutputStream.flush()
                                                }

                                                contentType.contains("text/html") && content is String -> {
                                                    htmlOutputStream.write(content.toByteArray())
                                                    htmlOutputStream.flush()
                                                }

                                                content is Multipart -> {
                                                    handlePart(content)
                                                }

                                                content is BodyPart -> {
                                                    handlePart(content)
                                                }
                                            }
                                        }
                                    }
                                }

                                val content = try {
                                    message.content
                                } catch (e: IOException) {
                                    throw e
                                }
                                handlePart(content)
                            }
                        }

                        textFile.inputStream().use { textInputStream ->
                            htmlFile.inputStream().use { htmlInputStream ->
                                rawFile.inputStream().use { rawInputStream ->
                                    Database.query {
                                        EmailContent.deleteWhere { EmailContent.id eq email.id }
                                        EmailContent.insert {
                                            val textFileLength = textFile.length()
                                            val htmlFileLength = htmlFile.length()
                                            val rawFileLength = rawFile.length()
                                            it[EmailContent.id] = email.id
                                            it[EmailContent.textContent] = if (textFileLength > 0) ExposedBlob(textInputStream) else null
                                            it[EmailContent.textSize] = if (textFileLength > 0) textFileLength else null
                                            it[EmailContent.htmlContent] = if (htmlFileLength > 0) ExposedBlob(htmlInputStream) else null
                                            it[EmailContent.htmlSize] = if (htmlFileLength > 0) htmlFileLength else null
                                            it[EmailContent.rawContent] = ExposedBlob(rawInputStream)
                                            it[EmailContent.rawSize] = rawFileLength
                                        }
                                    }
                                }
                            }
                        }

                        Database.query {
                            email.state = Email.State.Imported
                        }
                    } finally {
                        // Ensure temp files are always cleaned up
                        textFile?.delete()
                        htmlFile?.delete()
                        rawFile?.delete()
                    }
                }
            } catch (e: CancellationException) {
                logger.info("Import loop cancelled")
                throw e
            } catch (e: Exception) {
                logger.error("Error importing email ${request.emailId}: ${e.message}")
                // Continue to next message instead of stopping
            }
        }
    }

    /**
     * @param forceUpdateOfUsers If true, senders and recipients will be updated regardless of whether they already exist.
     */
    private suspend fun upsertMessage(
        message: Message,
        uid: Long,
        forceUpdateOfUsers: Boolean = false,
    ) {
        val messageId = message.getHeader("Message-ID")?.firstOrNull() ?:
            "overmail-fallback-message-id-${imapConfig.id.value}-${message.subject.orEmpty().hashCode()}-${message.sentDate.toInstant().toEpochMilli()}"

        val (email, isNew) = Database.query {
            val existing = Email
                .find { (Emails.imapConfig eq imapConfig.id.value) and (Emails.emailKey eq messageId) }
                .firstOrNull()

            if (existing == null) {
                val email = Email.new {
                    this.subject = message.subject?.takeIf { it.isNotEmpty() }
                    this.isRead = Flags.Flag.SEEN in message.flags
                    this.sentAt = Instant.fromEpochMilliseconds(message.sentDate.toInstant().toEpochMilli())
                    this.emailKey = messageId
                    this@new.imapConfig = this@MailsDaemon.imapConfig
                    this@new.folder = this@MailsDaemon.dbFolder
                    this.folderUid = uid
                    this.isRemoved = false
                    this.state = Email.State.Pending
                }

                pendingMessages.send(ImportRequest(email.id.value, false))

                return@query email to true
            }

            return@query existing to false
        }

        if (isNew || forceUpdateOfUsers) Database.query {
            val sentByIds = message.from
                .filterIsInstance<InternetAddress>()
                .map { from ->
                    getEmailUserOrCreate(from.personal?.takeIf { it.isNotBlank() } ?: from.address,
                        from.address).id.value
                }
                .distinct()

            val recipientIds = message.getRecipients(Message.RecipientType.TO)
                .orEmpty()
                .filterIsInstance<InternetAddress>()
                .map { to ->
                    getEmailUserOrCreate(to.personal?.takeIf { it.isNotBlank() } ?: to.address, to.address).id.value
                }
                .distinct()

            val ccRecipientIds = message.getRecipients(Message.RecipientType.CC)
                .orEmpty()
                .filterIsInstance<InternetAddress>()
                .map { cc ->
                    getEmailUserOrCreate(cc.personal?.takeIf { it.isNotBlank() } ?: cc.address, cc.address).id.value
                }
                .distinct()

            val bccRecipientIds = message.getRecipients(Message.RecipientType.BCC)
                .orEmpty()
                .filterIsInstance<InternetAddress>()
                .map { bcc ->
                    getEmailUserOrCreate(bcc.personal?.takeIf { it.isNotBlank() } ?: bcc.address,
                        bcc.address).id.value
                }
                .distinct()

            EmailSenders.deleteWhere { EmailSenders.email eq email.id }
            EmailRecipients.deleteWhere { EmailRecipients.email eq email.id }

            sentByIds.forEach { sentById ->
                EmailSenders.insert {
                    it[this.email] = email.id
                    it[this.sender] = sentById
                }
            }

            recipientIds.forEach { recipientId ->
                EmailRecipients.insert {
                    it[this.email] = email.id
                    it[this.recipient] = recipientId
                    it[this.type] = RecipientType.To
                }
            }

            ccRecipientIds.forEach { ccRecipientId ->
                EmailRecipients.insert {
                    it[this.email] = email.id
                    it[this.recipient] = ccRecipientId
                    it[this.type] = RecipientType.Cc
                }
            }

            bccRecipientIds.forEach { bccRecipientId ->
                EmailRecipients.insert {
                    it[this.email] = email.id
                    it[this.recipient] = bccRecipientId
                    it[this.type] = RecipientType.Bcc
                }
            }
        }

        if (!isNew) {
            val isSeenOnMailServer = Flags.Flag.SEEN in message.flags

            Database.query {
                email.isRemoved = false
                email.isRead = isSeenOnMailServer
                email.folderUid = uid
                email.folder = dbFolder
            }
        }
    }

    private fun getEmailUserOrCreate(name: String, email: String): EmailUser {
        val name = name
            .trim()
            .takeIf { it.isNotEmpty() }
        return EmailUser.find { (EmailUsers.email eq email) and (EmailUsers.displayName eq (name ?: email)) }.firstOrNull() ?: EmailUser.new {
            this.displayName = name ?: email
            this.email = email
        }
    }

    fun stop() {
        logger.info("Stopping MailsDaemon")
        isRunning = false
        syncJob?.cancel()
        importJob?.cancel()
        pendingMessages.close()
        try {
            storeInstance.close()
        } catch (e: Exception) {
            logger.error("Error closing store instance: ${e.message}")
        }
        try {
            contentImporterInstance.close()
        } catch (e: Exception) {
            logger.error("Error closing content importer instance: ${e.message}")
        }
    }
}

data class ImportRequest(
    val emailId: Int,
    val forceUpdate: Boolean
)