package dev.babies.overmail.api.mail.item.read

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.api.mail.getMail
import dev.babies.overmail.api.webapp.realtime.folders.notifyFolderChange
import dev.babies.overmail.api.webapp.realtime.mails.notifyEmailChange
import dev.babies.overmail.data.Database
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
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
                    // TODO
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