package dev.babies.overmail.api.webapp.email

import com.sun.mail.imap.IMAPFolder
import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.ImapFolder
import dev.babies.overmail.mail.daemon.DaemonManagerPlugin
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.UIDFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun Route.moveEmail() {
    authenticate(AUTHENTICATION_NAME) {
        post {
            val (email, _) = call.getEmail() ?: return@post
            val request = call.receive<MoveRequest>()
            val toFolderId = request.toFolderId.toInt()

            val sourceFolder = Database.query { email.folder }

            val destinationFolder = Database.query { ImapFolder.findById(toFolderId) }
            if (destinationFolder == null) {
                call.respond(status = HttpStatusCode.NotFound, "Folder not found")
                return@post
            }
            if (Database.query { destinationFolder.imapConfig.id.value != email.imapConfig.id.value }) {
                call.respond(status = HttpStatusCode.BadRequest, "Folder does not belong to the same account as the email")
                return@post
            }


            CoroutineScope(Dispatchers.IO).launch {
                val imapConfig = Database.query { email.imapConfig }
                val messageUid = Database.query { email.folderUid }

                val imapDaemon = DaemonManagerPlugin.getDaemonForImapConfig(imapConfig.id.value) ?: throw IllegalStateException("No IMAP daemon for config ${imapConfig.id.value}")
                val sourceInstance = imapDaemon.folderDaemon.storeInstances[sourceFolder.id.value] ?: throw IllegalStateException("No store instance for source folder ${email.folder.id.value}")

                sourceInstance.withStore { store ->
                    val sourceFolderImap = store.getFolder(sourceFolder.folderPath.replace("/", store.defaultFolder.separator.toString())) as IMAPFolder
                    val destinationFolderImap = store.getFolder(destinationFolder.folderPath.replace("/", store.defaultFolder.separator.toString())) as IMAPFolder

                    sourceFolderImap.open(Folder.READ_WRITE)
                    destinationFolderImap.open(Folder.READ_WRITE)

                    sourceFolderImap as UIDFolder
                    destinationFolderImap as UIDFolder

                    val newUid = try {
                        val message = sourceFolderImap.getMessageByUID(messageUid)
                        val newUid = sourceFolderImap.copyUIDMessages(arrayOf(message), destinationFolderImap).first().uid
                        message.setFlag(Flags.Flag.DELETED, true)
                        newUid
                    } finally {
                        sourceFolderImap.close(true)
                        destinationFolderImap.close(false)
                    }

                    Database.query {
                        email.folderUid = newUid
                        email.folder = destinationFolder
                    }
                }
            }

            call.respondText("Email moved")
        }
    }
}

@Serializable
data class MoveRequest(
    @SerialName("target_folder_id") val toFolderId: String
)