package dev.babies.overmail.mail.daemon

import com.sun.mail.imap.IMAPFolder
import dev.babies.overmail.api.web.realtime.folders.folderChange
import dev.babies.overmail.api.web.realtime.mails.emailChange
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
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

const val IMPORT_CHUNK_SIZE = 20

class ImapFolderSynchronizer(
    private val imapConfig: ImapConfig,
    private val databaseFolder: ImapFolder,
    private val connectionFactory: ImapConfigurationManager.ConnectionFactory
) {

    var idleJob: Job? = null
    var importJob: Job? = null

    private val logger = LoggerFactory.getLogger("${imapConfig.email}/${databaseFolder.folderName}")

    fun start() {
        val idlingConnection = connectionFactory()
        val importConnection = connectionFactory()

        idleJob = CoroutineScope(Dispatchers.IO).launch {
            idlingConnection.use { idlingConnection ->
                val folderName = Database.query { databaseFolder.getPath().joinToString(idlingConnection.defaultFolder.separator.toString()) }
                val folder = idlingConnection.getFolder(folderName) as IMAPFolder
                folder.open(IMAPFolder.READ_WRITE)

                folder.addMessageCountListener(object : MessageCountListener {
                    override fun messagesAdded(e: MessageCountEvent?) {
                        if (e == null) return
                        logger.info("${e.messages.size} new message(s) in ${folder.name}")

                        CoroutineScope(Dispatchers.IO).launch {
                            e.messages.forEach {
                                insertOrSkipEmail(it, folder.getUID(it), isReadOnly = false)
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
                        Database.query {
                            Email
                                .find { (Emails.imapConfig eq imapConfig.id.value) and (Emails.folderUid eq uid)}
                                .firstOrNull()
                                ?.apply {
                                    this.isRemoved = true
                                }
                        }
                        return@addMessageChangedListener
                    }


                    CoroutineScope(Dispatchers.IO).launch {
                        insertOrSkipEmail(message, uid, isReadOnly = false)
                    }
                }

                logger.info("Watching for new messages in ${folder.fullName}")
                while (true) {
                    folder.idle()
                    folder.messageCount
                    delay(5.seconds)
                }
            }
        }

        importJob = CoroutineScope(Dispatchers.IO).launch {
            val folderName = Database.query { databaseFolder.getPath().joinToString(idlingConnection.defaultFolder.separator.toString()) }
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

                folder.messages
                    .toList()
                    .sortedWith(
                        compareByDescending<Message> { folder.getUID(it) !in existingEmailIds }
                            .thenByDescending { it.sentDate }
                    )
                    .chunked(IMPORT_CHUNK_SIZE)
                    .map { it.toTypedArray() }
                    .forEachIndexed { i, messages ->
                        logger.debug("Importing batch ${i + 1} of ${folder.messageCount / IMPORT_CHUNK_SIZE + 1}")

                        val fetchProfile = FetchProfile()
                        fetchProfile.add(FetchProfile.Item.ENVELOPE)
                        fetchProfile.add(FetchProfile.Item.FLAGS)
                        fetchProfile.add(FetchProfile.Item.CONTENT_INFO)
                        fetchProfile.add("Message-ID")
                        folder.fetch(messages, fetchProfile)

                        messages.forEach { message ->
                            try {
                                insertOrSkipEmail(message, folder.getUID(message), isReadOnly = true)
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

                logger.info("Finished importing messages in ${folder.name}")
            }

            run deleteMails@{
                logger.info("Deleting all messages in ${folder.name} that don't exist on the IMAP server")

                val serverMessageUids = folder.messages.toList().map { folder.getUID(it) }

                Database.query {
                    Emails.deleteWhere { (Emails.imapConfig eq this@ImapFolderSynchronizer.imapConfig.id.value) and (Emails.folder eq databaseFolder.id.value) and (Emails.folderUid notInList serverMessageUids) }
                }

                logger.info("Finished deleting messages in ${folder.name}")
            }
        }
    }

    fun stop() {
        idleJob?.cancel()
        importJob?.cancel()
    }

    val mutexMap = mutableMapOf<Pair<String, Int>, Mutex>()

    suspend fun insertOrSkipEmail(message: Message, uId: Long, isReadOnly: Boolean) {
        val identifier = message.getHeader("Message-ID")?.firstOrNull() ?: "no-id"
        mutexMap.getOrPut(identifier to imapConfig.id.value) { Mutex() }.withLock {
            val existingEmail = Database.query {
                Emails
                    .select(Emails.columns)
                    .where { (Emails.emailKey eq identifier) and (Emails.imapConfig eq imapConfig.id.value) }
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

            val rawEmail: String?
            if (existingEmail == null) {
                readMultipart(message.content, textBody, htmlBody)
                val byteArrayOutputStream = ByteArrayOutputStream()
                message.writeTo(byteArrayOutputStream)
                rawEmail = byteArrayOutputStream.toString(Charsets.UTF_8.name())

                // set isSeen back to the original state because calling writeTo marks it as seen
                if (!isReadOnly) message.setFlag(Flags.Flag.SEEN, isSeen)
            } else {
                rawEmail = null
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
                    this.textBody = textBody.toString()
                    this.htmlBody = htmlBody.toString()
                    this.isRead = message.isSet(Flags.Flag.SEEN)
                    this.sentAt = Instant.fromEpochMilliseconds(message.sentDate.toInstant().toEpochMilli())
                    this.emailKey = identifier
                    this.folder = this@ImapFolderSynchronizer.databaseFolder
                    this.imapConfig = this@ImapFolderSynchronizer.imapConfig
                    this.folderUid = uId
                    this.rawSource = ExposedBlob(rawEmail!!.toByteArray(Charsets.UTF_8)) // should never be null if the email was inserted before
                }
            }

            if (isReadChanged) {
                folderChange(this.databaseFolder.id.value)
                emailChange(email.id.value)
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
        }
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
        return EmailUser.find { EmailUsers.email eq email }.firstOrNull() ?: EmailUser.new {
            this.displayName = name
            this.email = email
        }
    }
}