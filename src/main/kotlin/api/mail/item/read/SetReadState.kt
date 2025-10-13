package dev.babies.overmail.api.mail.item.read

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.api.mail.getMail
import dev.babies.overmail.data.Database
import dev.babies.overmail.mail.daemon.DaemonManagerPlugin
import dev.babies.overmail.mail.daemon.StoreInstance
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jakarta.mail.Flags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

fun Route.setReadState() {
    val logger = LoggerFactory.getLogger("SetReadState")

    authenticate(AUTHENTICATION_NAME) {
        put {
            val request = call.receive<SetReadStateRequest>()
            val email = call.getMail() ?: return@put

            Database.query { email.isRead = request.read }
            val folder = Database.query { email.folder }

            call.respond("Ok")

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val imapConfig = Database.query { email.imapConfig }
                    val messageUid = Database.query { email.folderUid }

                    val imapDaemon = DaemonManagerPlugin.getDaemonForImapConfig(imapConfig.id.value) ?: throw IllegalStateException("No IMAP daemon for config ${imapConfig.id.value}")
                    val instance = imapDaemon.folderDaemon.storeInstances[folder.id.value] ?: throw IllegalStateException("No store instance for folder ${folder.id.value}")

                    instance.withFolder(folderMode = StoreInstance.FolderMode.ReadWrite) { folder ->
                        val message = folder.getMessageByUID(messageUid) ?: throw IllegalStateException("No message with UID $messageUid in folder ${folder.fullName}")
                        message.setFlag(Flags.Flag.SEEN, request.read)
                        folder.expunge()
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