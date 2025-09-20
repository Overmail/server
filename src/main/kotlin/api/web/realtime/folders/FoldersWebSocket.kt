package dev.babies.overmail.api.web.realtime.folders

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.api.web.realtime.RealtimeManager
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
            RealtimeManager.addSession(user.id.value, this)

            try {
                pushFoldersToSession(this, Database.query { getFoldersForUserId(user.id.value, null) })

                for (frame in incoming) { frame as? Frame.Text ?: continue }
            } finally {
                RealtimeManager.removeSession(user.id.value, this)
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
                .apply { if (filterFolderId != null) this.and(ImapFolders.id eq filterFolderId) }
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

suspend fun pushFoldersToSession(session: WebSocketServerSession, folders: List<FolderWebSocketEvent.NewFolders.Folder>) {
    session.sendSerialized<FolderWebSocketEvent>(FolderWebSocketEvent.NewFolders(folders))
}

suspend fun folderChange(folderId: Int) {
    val folder = Database.query { ImapFolder.findById(folderId)!! }
    if (Database.query { folder.parentFolder } == null) return
    val user = Database.query { folder.imapConfig.owner }
    val newFolderDto = Database.query { getFoldersForUserId(user.id.value, folderId) }
    RealtimeManager.getSessions(user.id.value).forEach { session ->
        pushFoldersToSession(session, newFolderDto)
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
            @SerialName("name") val name: String,
            @SerialName("unread_count") val unreadCount: Int,
            @SerialName("id") val id: String,
            @SerialName("account_id") val accountId: Int,
            @SerialName("parent_id") val parentId: String?
        )
    }
}