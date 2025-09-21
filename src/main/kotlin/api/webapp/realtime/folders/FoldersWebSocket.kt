package dev.babies.overmail.api.webapp.realtime.folders

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.api.webapp.realtime.RealtimeManager
import dev.babies.overmail.api.webapp.realtime.RealtimeSubscription
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select

fun Route.foldersWebSocket() {
    authenticate(AUTHENTICATION_NAME) {
        webSocket {
            val principal = this.call.principal<JWTPrincipal>()!!
            val userId = principal.payload.getClaim("id").asInt()
            val user = Database.query { User.findById(userId)!! }

            val session = RealtimeSubscription.FoldersSubscription(
                userId = user.id.value,
                session = this
            )

            RealtimeManager.addSession(
                userId = user.id.value,
                session = session
            )

            try {
                pushFoldersToSession(this, Database.query { getFoldersForUserId(user.id.value, null) })

                for (frame in incoming) { frame as? Frame.Text ?: continue }
            } finally {
                RealtimeManager.removeSession(user.id.value, session)
            }
        }
    }
}

private fun getFoldersForUserId(userId: Int, filterFolderId: Int?): List<FolderWebSocketEvent.NewFolders.Folder> {
    val rootFolderId = ImapFolders
        .leftJoin(ImapConfigs)
        .select(ImapFolders.id)
        .where { (ImapConfigs.owner eq userId) and (ImapFolders.parentFolder eq null) }
        .firstOrNull()
        ?.let { it[ImapFolders.id].value }

    val unreadSum = Sum(
        Case()
            .When(Emails.isRead eq false, intLiteral(1))
            .Else(intLiteral(0)),
        IntegerColumnType()
    ).alias("unread")

    return ImapFolders
        .leftJoin(ImapConfigs, { ImapFolders.imapConfig }, { ImapConfigs.id })
        .leftJoin(Emails, { Emails.folder }, { ImapFolders.id })
        .select(
            ImapFolders.folderName,
            ImapFolders.id,
            ImapConfigs.id,
            ImapFolders.parentFolder,
            unreadSum
        )
        .where {
            ((ImapConfigs.owner eq userId) and (ImapFolders.parentFolder neq null))
                .let { if (filterFolderId != null) it.and(ImapFolders.id eq filterFolderId) else it }
        }
        .groupBy(ImapFolders.id, ImapConfigs.id)
        .orderBy(ImapFolders.id)
        .map {
            FolderWebSocketEvent.NewFolders.Folder(
                name = it[ImapFolders.folderName],
                unreadCount = it[unreadSum] ?: 0,
                id = it[ImapFolders.id].value.toString(),
                accountId = it[ImapConfigs.id].value,
                parentId = it[ImapFolders.parentFolder].let { parentFolderId ->
                    if (parentFolderId?.value  == rootFolderId) null else parentFolderId.toString()
                }
            )
        }
}

private suspend fun pushFoldersToSession(session: WebSocketServerSession, folders: List<FolderWebSocketEvent.NewFolders.Folder>) {
    session.sendSerialized<FolderWebSocketEvent>(FolderWebSocketEvent.NewFolders(folders))
}

/**
 * Call when a folder is created or renamed or when a mail-seen flag is changed.
 * This will update the folder list for the user who owns the folder.
 * @param folderId the id of the folder that was created or renamed
 */
suspend fun notifyFolderChange(folderId: Int) {
    val folder = Database.query { ImapFolder.findById(folderId)!! }
    if (Database.query { folder.parentFolder } == null) return
    val user = Database.query { folder.imapConfig.owner }
    val newFolderDto = Database.query { getFoldersForUserId(user.id.value, folderId) }
    RealtimeManager.getFoldersWatcher(user.id.value).forEach { session ->
        pushFoldersToSession(session.session, newFolderDto)
    }
}

@Serializable
sealed class FolderWebSocketEvent {
    @Serializable
    @SerialName("new-folders")
    data class NewFolders(
        val folders: List<Folder>
    ): FolderWebSocketEvent() {
        @Serializable
        data class Folder(
            @SerialName("id") val id: String,
            @SerialName("name") val name: String,
            @SerialName("unread_count") val unreadCount: Int,
            @SerialName("account_id") val accountId: Int,
            @SerialName("parent_id") val parentId: String?
        )
    }
}