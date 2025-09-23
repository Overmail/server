package dev.babies.overmail.mail.daemon

import com.sun.mail.imap.IMAPFolder
import dev.babies.overmail.api.webapp.realtime.folders.notifyFolderChange
import dev.babies.overmail.api.webapp.realtime.mails.notifyEmailChange
import dev.babies.overmail.api.webapp.realtime.mails.notifyEmailDelete
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.*
import jakarta.mail.*
import jakarta.mail.event.MessageCountEvent
import jakarta.mail.event.MessageCountListener
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeUtility
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

const val IMPORT_CHUNK_SIZE = 20

class ImapFolderSynchronizer(
    private val imapConfig: ImapConfig,
    private val databaseFolder: ImapFolder,
    private val connectionFactory: ImapConfigurationManager.ConnectionFactory
) {

    private val logger = LoggerFactory.getLogger("${imapConfig.email}/${databaseFolder.folderName}")
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {

        mainScope.launch {
            launch idleWatcher@{
                // Resolve the full path of the folder once (path segments), separator is applied per-connection
                val pathSegments = Database.query { databaseFolder.getPath() }

                while (isActive) {
                    val store = connectionFactory()
                    try {
                        store.use { s ->
                            val folderName = pathSegments.joinToString(s.defaultFolder.separator.toString())
                            val folder = s.getFolder(folderName) as IMAPFolder
                            folder.open(IMAPFolder.READ_WRITE)

                            // Register listeners for this folder instance
                            folder.addMessageCountListener(object : MessageCountListener {
                                override fun messagesAdded(e: MessageCountEvent?) {
                                    if (e == null) return
                                    logger.info("${e.messages.size} new message(s) in ${folder.name}")

                                    CoroutineScope(Dispatchers.IO).launch {
                                        e.messages.forEach {
                                            insertOrSkipEmail(it, folder.getUID(it))
                                        }
                                    }
                                }

                                override fun messagesRemoved(e: MessageCountEvent?) {}
                            })

                            folder.addMessageChangedListener { event ->
                                val message = event.message
                                logger.info("Message ${message.messageNumber} changed")

                                val uid = folder.getUID(message)
                                if (message.isSet(Flags.Flag.DELETED)) {
                                    val email = Database.query {
                                        Email
                                            .find { (Emails.imapConfig eq imapConfig.id.value) and (Emails.folderUid eq uid)}
                                            .firstOrNull()
                                    }

                                    if (email != null) CoroutineScope(Dispatchers.IO).launch {
                                        notifyEmailDelete(email.id.value)
                                        if (!email.isRead) notifyFolderChange(databaseFolder.id.value)
                                        Database.query {
                                            email.isRemoved = true
                                        }
                                    }
                                    return@addMessageChangedListener
                                }

                                CoroutineScope(Dispatchers.IO).launch {
                                    insertOrSkipEmail(message, uid)
                                }
                            }

                            logger.info("Watching for new messages in ${folder.fullName}")
                            while (isActive) {
                                try {
                                    folder.idle()
                                    // short noop to force event dispatch in some servers
                                    folder.messageCount
                                    delay(5.seconds)
                                } catch (e: FolderClosedException) {
                                    logger.warn("Folder closed during IDLE on ${folder.fullName}: ${e.message}")
                                    break // recreate connection and folder
                                } catch (e: MessagingException) {
                                    logger.warn("Messaging exception during IDLE on ${folder.fullName}: ${e.message}")
                                    break // recreate connection and folder
                                } catch (e: Exception) {
                                    logger.warn("Unexpected exception during IDLE: ${e.message}", e)
                                    break // recreate connection and folder
                                }
                            }
                            folder.close(false)
                        }
                    } catch (e: Exception) {
                        logger.error("Recreating IMAP connection after error: ${e.message}", e)
                    } finally {
                        try { store.close() } catch (_: Exception) {}
                    }

                    // Backoff before retrying connection
                    delay(2.seconds)
                }
            }
            launch backgroundUpdater@{
                while (isActive) {
                    val importConnection = connectionFactory()
                    val folderName = Database.query { databaseFolder.getPath().joinToString(importConnection.defaultFolder.separator.toString()) }
                    val folder = importConnection.getFolder(folderName) as IMAPFolder
                    folder.open(IMAPFolder.READ_ONLY)

                    run importMails@{
                        logger.info("Importing all messages in ${folder.name}")

                        val existingEmailIds = Database.query {
                            Emails
                                .select(Emails.folderUid)
                                .where { (Emails.imapConfig eq imapConfig.id.value) and (Emails.folder eq databaseFolder.id.value) }
                                .map { it[Emails.folderUid] }
                        }
                            .distinct()
                            .sorted()

                        val messages = folder.messages.toList()

                        val pendingMessages = messages
                            .filter { folder.getUID(it) !in existingEmailIds }
                            .chunked(IMPORT_CHUNK_SIZE)

                        val existingMessages = messages
                            .filter { folder.getUID(it) in existingEmailIds }
                            .chunked(IMPORT_CHUNK_SIZE)

                        if (pendingMessages.isNotEmpty()) logger.info("Found ${pendingMessages.flatten().size} new messages in ${folder.name}")
                        pendingMessages
                            .map { it.toTypedArray() }
                            .forEachIndexed { i, messages ->
                                logger.info("Importing batch ${i + 1} of ${pendingMessages.size}")

                                val fetchProfile = FetchProfile()
                                fetchProfile.add(FetchProfile.Item.ENVELOPE)
                                fetchProfile.add(FetchProfile.Item.FLAGS)
                                fetchProfile.add(FetchProfile.Item.CONTENT_INFO)
                                fetchProfile.add("Message-ID")
                                folder.fetch(messages, fetchProfile)

                                messages.forEach { message ->
                                    try {
                                        insertOrSkipEmail(message, folder.getUID(message))
                                    } catch (e: Exception) {
                                        logger.error(
                                            """
                            Failed to import message ${folder.getUID(message)} in ${folder.name}.
                            Subject: ${message.subject}
                            From: ${message.from.joinToString(", ")}
                            Sent Date: ${message.sentDate}
                            Exception:
                            
                        """.trimIndent() + e.stackTraceToString()
                                        )
                                    }
                                }
                            }

                        if (existingMessages.isNotEmpty()) logger.info("Updating metadata for ${existingMessages.flatten().size} existing messages in ${folder.name}")
                        val flagFetchProfile = FetchProfile()
                        flagFetchProfile.add(FetchProfile.Item.FLAGS)
                        existingMessages
                            .forEach { existingMessageChunk ->
                                folder.fetch(existingMessageChunk.toTypedArray(), flagFetchProfile)
                                existingMessageChunk.forEach forEachMessage@{ message ->
                                    val existing = Database.query { Email.find { Emails.folderUid eq folder.getUID(message) and (Emails.imapConfig eq imapConfig.id.value) }.firstOrNull() }
                                    if (existing == null) {
                                        logger.warn("Message ${folder.getUID(message)} in ${folder.name} is not in the database, skipping")
                                        return@forEachMessage
                                    }

                                    val isRead = message.isSet(Flags.Flag.SEEN)
                                    if (existing.isRead != isRead) {
                                        Database.query { existing.isRead = isRead }
                                        notifyEmailChange(existing.id.value)
                                        notifyFolderChange(databaseFolder.id.value)
                                    }
                                }
                            }

                        logger.info("Finished importing messages in ${folder.name}")
                    }

                    run deleteMails@{
                        val serverMessageUids = folder.messages.toList().map { folder.getUID(it) }

                        Database.query {
                            Emails
                                .select(Emails.id, Emails.subject, Emails.sentAt, Emails.isRead, Emails.textBody)
                                .where{ (Emails.imapConfig eq this@ImapFolderSynchronizer.imapConfig.id.value) and (Emails.folder eq databaseFolder.id.value) and (Emails.folderUid notInList serverMessageUids) }
                                .map { Email.wrapRow(it) }
                                .onEach { logger.info("Deleting email ${it.subject} (${it.id.value}) in ${folder.name}") }
                                .forEach { it.delete() }
                        }

                        logger.info("Finished deleting messages in ${folder.name}")
                    }
                    delay(3.minutes)
                }
            }
        }
    }

    fun stop() {
        mainScope.cancel()
    }

    val mutexMap = mutableMapOf<Pair<String, Int>, Mutex>()

    suspend fun insertOrSkipEmail(message: Message, uId: Long) {
        val identifier = message.getHeader("Message-ID")?.firstOrNull() ?: "no-id"
        mutexMap.getOrPut(identifier to imapConfig.id.value) { Mutex() }.withLock {
            val existingEmail = Database.query {
                Emails
                    .select(Emails.columns)
                    .where { (Emails.emailKey eq identifier) and (Emails.imapConfig eq imapConfig.id.value) and (Emails.folder eq databaseFolder.id.value) }
                    .firstOrNull()
                    ?.let { Email.wrapRow(it) }
            }

            val senderIds = Database.query {
                message.from.mapNotNull { sender ->
                    if (sender is InternetAddress) {
                        val display = if (sender.personal != null && sender.personal.startsWith("=?UTF-8?"))
                            MimeUtility.decodeText(sender.toString().substringBeforeLast(" <"))
                        else sender.personal ?: sender.address

                        val user = getEmailUserOrCreate(display, sender.address)

                        return@mapNotNull user.id.value
                    }
                    return@mapNotNull null
                }.distinct()
            }

            val recipientIds: List<Pair<Int, RecipientType>> = Database.query {
                message.getRecipients(Message.RecipientType.TO)
                    .orEmpty()
                    .mapNotNull { recipient ->
                        if (recipient is InternetAddress) {
                            val display =
                                if (recipient.personal != null && recipient.personal.startsWith("=?UTF-8?"))
                                    MimeUtility.decodeText(recipient.toString().substringBeforeLast(" <"))
                                else recipient.personal ?: recipient.address

                            val user = getEmailUserOrCreate(display, recipient.address)
                            return@mapNotNull (user.id.value to RecipientType.To)
                        }

                        return@mapNotNull null
                    }
                    .plus(
                        message.getRecipients(Message.RecipientType.CC)
                            .orEmpty()
                            .mapNotNull { recipient ->
                                if (recipient is InternetAddress) {
                                    val display =
                                        if (recipient.personal != null && recipient.personal.startsWith("=?UTF-8?"))
                                            MimeUtility.decodeText(recipient.toString().substringBeforeLast(" <"))
                                        else recipient.personal ?: recipient.address

                                    val user = getEmailUserOrCreate(display, recipient.address)
                                    return@mapNotNull (user.id.value to RecipientType.Cc)
                                }
                                return@mapNotNull null
                            }
                    )
                    .plus(
                        message.getRecipients(Message.RecipientType.BCC)
                            .orEmpty()
                            .mapNotNull { recipient ->
                                if (recipient is InternetAddress) {
                                    val display =
                                        if (recipient.personal != null && recipient.personal.startsWith("=?UTF-8?"))
                                            MimeUtility.decodeText(recipient.toString().substringBeforeLast(" <"))
                                        else recipient.personal ?: recipient.address

                                    val user = getEmailUserOrCreate(display, recipient.address)
                                    return@mapNotNull (user.id.value to RecipientType.Bcc)
                                }
                                return@mapNotNull null
                            }
                    )
                    .distinctBy { it.first.toString() + it.second }
            }

            val textBody = StringBuilder()
            val htmlBody = StringBuilder()

            val isSeen = message.isSet(Flags.Flag.SEEN)
            if (existingEmail == null) {
                readMultipart(message.content, textBody, htmlBody)
            }

            var isReadChanged = false
            val email = Database.query {
                existingEmail?.apply {
                    if(this.isRead != isSeen) {
                        this.isRead = isSeen
                        isReadChanged = true
                    }
                    this.isRemoved = false
                    if (this@apply.folder.id.value != databaseFolder.id.value) this@apply.folder = databaseFolder
                } ?: Email.new {
                    this.subject = message.subject
                    this.textBody = textBody
                        .toString()
                        .replace("\r\n", "\n")
                    this.htmlBody = htmlBody.toString().takeIf { it.isNotBlank() }
                    this.isRead = message.isSet(Flags.Flag.SEEN)
                    this.sentAt = Instant.fromEpochMilliseconds(message.sentDate.toInstant().toEpochMilli())
                    this.emailKey = identifier
                    this.folder = this@ImapFolderSynchronizer.databaseFolder
                    this.imapConfig = this@ImapFolderSynchronizer.imapConfig
                    this.folderUid = uId
                    this.rawSource = ExposedBlob(message.inputStream)
                }
            }

            if (existingEmail == null && !isSeen) {
                // email is new and not read yet, needs to be marked as unread, because the content has been fetched now
                // thus the email has been probably marked as read by the imap server
                message.setFlag(Flags.Flag.SEEN, false)
            }

            if (isReadChanged) {
                notifyFolderChange(this.databaseFolder.id.value)
                notifyEmailChange(email.id.value)
            }

            if (existingEmail == null) Database.query {
                senderIds
                    .filter { email.sentBy.none { sender -> sender.id.value == it } }
                    .forEach { senderId ->
                        EmailSenders.insert {
                            it[this.email] = email.id
                            it[this.sender] = senderId
                        }
                    }
            }

            if (existingEmail == null) Database.query {
                recipientIds
                    .filter { email.receivedBy.none { receiver -> receiver.recipient.id.value == it.first && receiver.type == it.second }}
                    .forEach { (recipientId, type) ->
                        EmailRecipients.insert {
                            it[this.email] = email.id
                            it[this.recipient] = recipientId
                            it[this.type] = type
                        }
                    }
            }

            if (existingEmail == null) {
                notifyEmailChange(email.id.value)
                if (!email.isRead) notifyFolderChange(this.databaseFolder.id.value)
            }
        }
        mutexMap.remove(identifier to imapConfig.id.value)
    }

    private fun readMultipart(
        content: Any,
        textBody: StringBuilder,
        htmlBody: StringBuilder
    ) {
        when (content) {
            is Multipart -> {
                repeat(content.count) { i ->
                    val bodyPart: BodyPart = content.getBodyPart(i)
                    val contentType = bodyPart.contentType.lowercase()
                    if (contentType.startsWith("text/plain")) textBody.append(bodyPart.content as String)
                    else if (contentType.startsWith("text/html")) htmlBody.append(bodyPart.content as String)
                    else if (contentType.startsWith("multipart/related") || contentType.startsWith("multipart/alternative")) {
                        readMultipart(bodyPart.content as Multipart, textBody, htmlBody)
                    }
                }
            }

            is String -> {
                if (
                    content.trim().lowercase().let {
                        it.startsWith("<html") ||
                                it.startsWith("<!doctype html>")
                    }
                ) htmlBody.append(content)
                else textBody.append(content)
            }
        }
    }

    private fun getEmailUserOrCreate(name: String, email: String): EmailUser {
        val name = name
            .trim()
            .dropLastWhile { it == '\'' }
            .dropWhile { it == '\'' }
            .takeIf { it.isNotEmpty() }
        return EmailUser.find { EmailUsers.email eq email }.firstOrNull() ?: EmailUser.new {
            this.displayName = name ?: email
            this.email = email
        }
    }
}