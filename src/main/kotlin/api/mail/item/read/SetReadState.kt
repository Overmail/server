package dev.babies.overmail.api.mail.item.read

import com.sun.mail.imap.IMAPFolder
import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.api.mail.getMail
import dev.babies.overmail.api.webapp.realtime.folders.notifyFolderChange
import dev.babies.overmail.api.webapp.realtime.mails.notifyEmailChange
import dev.babies.overmail.data.Database
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jakarta.mail.Flags
import jakarta.mail.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.*

fun Route.setReadState() {
    val logger = LoggerFactory.getLogger("SetReadState")

    authenticate(AUTHENTICATION_NAME) {
        put {
            val request = call.receive<SetReadStateRequest>()
            val email = call.getMail() ?: return@put

            Database.query { email.isRead = request.read }
            notifyEmailChange(email.id.value)
            val folder = Database.query { email.folder }
            val folderId = folder.id.value
            notifyFolderChange(folderId)

            call.respond("Ok")

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val imapConfig = Database.query { email.imapConfig }
                    val folderPathSegments = Database.query { folder.getPath() }
                    val messageUid = Database.query { email.folderUid }

                    val props = Properties().apply {
                        if (imapConfig.ssl) put("mail.store.protocol", "imaps")
                        // Keep reasonable defaults; no need to persist long-lived connection
                        put("mail.imap.partialfetch", "false")
                        put("mail.imaps.partialfetch", "false")
                        // Be tolerant of invalid Content-Transfer-Encoding values (e.g., "utf-8")
                        put("mail.mime.ignoreunknownencoding", "true")
                    }
                    val session = Session.getInstance(props)
                    val store = if (imapConfig.ssl) session.getStore("imaps") else session.getStore("imap")
                    try {
                        store.connect(imapConfig.host, imapConfig.port, imapConfig.username, imapConfig.password)
                        val folderFullName = folderPathSegments.joinToString(store.defaultFolder.separator.toString())
                        val imapFolder = store.getFolder(folderFullName) as IMAPFolder
                        if (!imapFolder.isOpen) imapFolder.open(IMAPFolder.READ_WRITE)

                        val message = imapFolder.getMessageByUID(messageUid)
                        if (message != null) {
                            message.setFlag(Flags.Flag.SEEN, request.read)
                        } else {
                            logger.warn("Could not find message by UID $messageUid in folder $folderFullName for ${imapConfig.email}")
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to update IMAP read state: ${e.message}", e)
                    } finally {
                        try { store.close() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    logger.error("Unexpected error while syncing read state to IMAP: ${e.message}", e)
                }
            }
        }
    }
}

@Serializable
data class SetReadStateRequest(
    @SerialName("is_read") val read: Boolean
)