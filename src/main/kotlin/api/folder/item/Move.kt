package dev.babies.overmail.api.folder.item

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.api.webapp.folder.getFolder
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.ImapFolder
import dev.babies.overmail.mail.daemon.DaemonManagerPlugin
import dev.babies.overmail.util.dropWhileIndexed
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun Route.moveFolder() {
    authenticate(AUTHENTICATION_NAME) {
        post {
            val (folder, _) = call.getFolder() ?: return@post
            val request = call.receive<Move>()
            val parentId = request.parentId?.toInt()
            val previousId = request.previousId?.toInt()

            val targetParentFolder = Database.query { parentId?.let { ImapFolder.findById(it) } }
            if (targetParentFolder == null && parentId != null) {
                call.respond(status = HttpStatusCode.NotFound, "Parent folder not found")
                return@post
            }

            val targetPreviousFolder = Database.query { previousId?.let { ImapFolder.findById(it) } }
            if (targetPreviousFolder == null && previousId != null) {
                call.respond(status = HttpStatusCode.NotFound, "Previous folder not found")
                return@post
            }

            Database.query {
                if (targetParentFolder?.id?.value != folder.parentFolder?.id?.value) {
                    // Folder got moved to another parent, hence an IMAP action must take place

                    if (targetParentFolder?.id?.value == folder.id.value) {
                        call.respond(status = HttpStatusCode.BadRequest, "Folder cannot be moved to itself")
                        return@query
                    }

                    var currentParent = folder.parentFolder
                    while (currentParent != null) {
                        if (currentParent.id.value == targetParentFolder?.id?.value) {
                            call.respond(status = HttpStatusCode.BadRequest, "Folder cannot be moved to its child")
                            return@query
                        }
                        currentParent = currentParent.parentFolder
                    }

                    val imapConfig = folder.imapConfig
                    val imapDaemon = DaemonManagerPlugin.getDaemonForImapConfig(imapConfig.id.value) ?: throw IllegalStateException("No IMAP daemon for config ${imapConfig.id.value}")

                    imapDaemon.folderDaemon.folderImporterMutex.withLock {
                        val foldersToRename = mutableListOf<ImapFolder>()
                        fun getChildren(folder: ImapFolder) {
                            foldersToRename.add(folder)
                            folder.children.forEach { getChildren(it) }
                        }
                        getChildren(folder)

                        val separator = imapDaemon.folderDaemon.defaultFolder!!.separator.toString()
                        val oldPrefix = folder.parentFolder?.folderPath?.split("/").orEmpty().filter { it.isNotBlank() }
                        val newPrefix = targetParentFolder?.folderPath?.split("/").orEmpty().filter { it.isNotBlank() }

                        fun getNameTransition(folder: ImapFolder): Pair<String, String> {
                            val currentName = folder.folderPath
                                .split("/")
                                .filter { it.isNotBlank() }

                            val newName = (newPrefix + currentName.dropWhileIndexed { index, value ->
                                if (index >= oldPrefix.size) return@dropWhileIndexed false
                                if (value == oldPrefix[index]) {
                                    return@dropWhileIndexed true
                                }
                                false
                            }).joinToString(separator)
                            return currentName.joinToString(separator) to newName
                        }

                        imapDaemon.folderDaemon.getStoreInstance().withStore { store ->
                            val (currentName, newName) = getNameTransition(folder)
                            val source = store.getFolder(currentName)
                            val destination = store.getFolder(newName)
                            source.renameTo(destination)
                        }

                        foldersToRename.forEach { folder ->
                            val (currentName, newName) = getNameTransition(folder)
                            println("$currentName -> $newName")
                            folder.folderPath = newName
                        }

                        folder.parentFolder = targetParentFolder
                    }
                }
            }
        }
    }
}

@Serializable
data class Move(
    @SerialName("parent_id") val parentId: String?,
    @SerialName("previous_id") val previousId: String?,
)