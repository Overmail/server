package dev.babies.overmail.api.webapp.sidebar.folders

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.ImapConfig
import dev.babies.overmail.data.model.ImapConfigs
import dev.babies.overmail.data.model.ImapFolder
import dev.babies.overmail.data.model.User
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq

fun Route.sidebarFolders() {
    authenticate(AUTHENTICATION_NAME) {
        get {
            val principal = this.call.principal<JWTPrincipal>()!!
            val userId = principal.payload.getClaim("id").asInt()
            val user = Database.query { User.findById(userId)!! }

            Database.query {
                val imapConfigs = ImapConfig.find { ImapConfigs.owner eq user.id }

                fun ImapFolder.mapFolder(): SidebarFoldersGetResponse.Account.Folder {
                    return SidebarFoldersGetResponse.Account.Folder(
                        id = this.id.value.toString(),
                        originalName = this.folderName,
                        customName = when (this.folderName.uppercase()) {
                            "INBOX" -> "Posteingang"
                            "SENT" -> "Gesendet"
                            "TRASH" -> "Papierkorb"
                            "DRAFTS" -> "Entwürfe"
                            "SPAM" -> "Spam"
                            else -> null
                        },
                        type = when (this.folderName.uppercase()) {
                            "INBOX" -> "inbox"
                            "SENT" -> "sent items"
                            "TRASH" -> "trash"
                            "DRAFTS" -> "drafts"
                            "SPAM" -> "spam"
                            "ARCHIVE" -> "archive"
                            else -> "other"
                        },
                        unreadCount = this.emails.count { !it.isRead && !it.isRemoved },
                        children = this.children.map { it.mapFolder() }
                    )
                }

                return@query SidebarFoldersGetResponse(
                    accounts = imapConfigs.map { imapConfig ->
                        SidebarFoldersGetResponse.Account(
                            id = imapConfig.id.value,
                            name = imapConfig.email,
                            folders = imapConfig
                                .folders
                                .filter { it.parentFolder == null }
                                .map { folder -> folder.mapFolder() }
                        )
                    }
                )
            }.let { call.respond(it) }
        }
    }
}

@Serializable
data class SidebarFoldersGetResponse(
    @SerialName("accounts") val accounts: List<Account>
) {
    @Serializable
    data class Account(
        @SerialName("id") val id: Int,
        @SerialName("name") val name: String,
        @SerialName("folders") val folders: List<Folder>
    ) {
        @Serializable
        data class Folder(
            @SerialName("id") val id: String,
            @SerialName("custom_name") val customName: String?,
            @SerialName("type") val type: String,
            @SerialName("original_name") val originalName: String,
            @SerialName("unread_count") val unreadCount: Int,
            @SerialName("children") val children: List<Folder>
        )
    }
}