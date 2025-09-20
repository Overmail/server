package dev.babies.overmail.api.web.realtime.mails

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.api.web.realtime.RealtimeManager
import dev.babies.overmail.api.web.realtime.RealtimeSubscription
import dev.babies.overmail.api.web.realtime.RealtimeSubscriptionType
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select

fun Route.mailsWebSocket() {
    authenticate(AUTHENTICATION_NAME) {
        route("/{folderId}") {
            webSocket {
                val principal = this.call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("id").asInt()
                val user = Database.query { User.findById(userId)!! }
                val folderId = this.call.parameters["folderId"]?.toInt()!!

                val folder = Database.query { ImapFolder.findById(folderId) }
                if (folder == null) {
                    send("Folder not found")
                    return@webSocket
                }

                if (Database.query { folder.imapConfig.owner.id.value != userId }) {
                    send("You don't have access to this folder")
                    return@webSocket
                }

                val session = RealtimeSubscription(
                    userId = user.id.value,
                    type = RealtimeSubscriptionType.Mails,
                    session = this
                )

                RealtimeManager.addSession(
                    userId = user.id.value,
                    session = session
                )

                try {
                    pushMailsToSession(session, Database.query { getMailsForUserId(user.id.value, folderId, null) })
                    for (frame in incoming) { frame as? Frame.Text ?: continue }
                } finally {
                    RealtimeManager.removeSession(user.id.value, session)
                }
            }
        }
    }
}

private fun getMailsForUserId(userId: Int, filterFolderId: Int, filterEmailId: Int?): List<MailWebSocketEvent.NewMails.Mail> {
    return Emails
        .leftJoin(ImapConfigs, { ImapConfigs.id }, { Emails.imapConfig })
        .leftJoin(ImapFolders, { ImapFolders.id }, { Emails.folder })
        .select(Emails.columns)
        .where { ImapConfigs.owner eq userId }
        .andWhere { ImapFolders.id eq filterFolderId }
        .let {
            if (filterEmailId != null) it.andWhere { Emails.id eq filterEmailId }
            else it
        }
        .orderBy(Emails.sentAt, SortOrder.DESC)
        .map { Email.wrapRow(it) }
        .map { email ->
            MailWebSocketEvent.NewMails.Mail(
                id = email.id.value.toString(),
                subject = email.subject,
                from = email.sentBy.joinToString { it.displayName },
                sentAt = email.sentAt.epochSeconds,
                isRead = email.isRead,
                hasAttachments = false,
                previewText = email.textBody.take(100)
                    .replace("\r\n", " ")
                    .replace("\n", " ")
            )
        }
}

suspend fun pushMailsToSession(session: RealtimeSubscription, mails: List<MailWebSocketEvent.NewMails.Mail>) {
    session.session.sendSerialized<MailWebSocketEvent>(MailWebSocketEvent.NewMails(mails))
}

suspend fun emailChange(emailId: Int) {
    val email = Database.query { Email.findById(emailId)!! }
    val user = Database.query { email.imapConfig.owner }
    val newEmailDto = Database.query { getMailsForUserId(user.id.value, email.folder.id.value, emailId) }
    RealtimeManager.getSessions(user.id.value, RealtimeSubscriptionType.Mails).forEach { session ->
        pushMailsToSession(session, newEmailDto)
    }
}

@Serializable
sealed class MailWebSocketEvent {
    @Serializable
    @SerialName("new-mails")
    data class NewMails(
        val mails: List<Mail>
    ): MailWebSocketEvent() {
        @Serializable
        data class Mail(
            @SerialName("id") val id: String,
            @SerialName("subject") val subject: String?,
            @SerialName("from") val from: String,
            @SerialName("sent_at") val sentAt: Long,
            @SerialName("is_read") val isRead: Boolean,
            @SerialName("has_attachments") val hasAttachments: Boolean,
            @SerialName("preview_text") val previewText: String,
        )
    }
}