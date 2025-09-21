package dev.babies.overmail.api.web.realtime.mails

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.api.web.realtime.RealtimeManager
import dev.babies.overmail.api.web.realtime.RealtimeSubscription
import dev.babies.overmail.api.web.realtime.RealtimeSubscriptionType
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.*
import dev.babies.overmail.json
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
import kotlin.math.min

private const val WEBSOCKET_EMAIL_CHUNK_SIZE = 100L

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
                    val emailCountOnStart = Database.query { getEmailCount(user.id.value, folderId) }

                    var fetchedMails = min(emailCountOnStart, WEBSOCKET_EMAIL_CHUNK_SIZE)

                    sendMailCountToSession(session, emailCountOnStart, fetchedMails)
                    pushMailsToSession(session, Database.query { getMailsForUserId(user.id.value, folderId, null, offset = 0L, limit = WEBSOCKET_EMAIL_CHUNK_SIZE.toInt()) })
                    for (frame in incoming) {
                        val messageText = frame as? Frame.Text ?: continue
                        val message = json.decodeFromString<MailWebSocketMessage>(messageText.readText())

                        when (message) {
                            is MailWebSocketMessage.RequestNextChunk -> {
                                val total = Database.query { getEmailCount(user.id.value, folderId) }
                                fetchedMails = min(total, fetchedMails + WEBSOCKET_EMAIL_CHUNK_SIZE)
                                sendMailCountToSession(session, total, fetchedMails)
                                pushMailsToSession(session, Database.query { getMailsForUserId(user.id.value, folderId, null, offset = fetchedMails, limit = WEBSOCKET_EMAIL_CHUNK_SIZE.toInt()) })
                            }
                        }
                    }
                } finally {
                    RealtimeManager.removeSession(user.id.value, session)
                }
            }
        }
    }
}

private fun Emails.filterForUserAndFolder(userId: Int, folderId: Int) = this
    .leftJoin(ImapConfigs, { ImapConfigs.id }, { Emails.imapConfig })
    .leftJoin(ImapFolders, { ImapFolders.id }, { Emails.folder })
    .select(Emails.id, Emails.subject, Emails.sentAt, Emails.isRead, Emails.textBody)
    .where { ImapConfigs.owner eq userId }
    .andWhere { ImapFolders.id eq folderId }

private fun getMailsForUserId(userId: Int, filterFolderId: Int, filterEmailId: Int?, limit: Int?, offset: Long?): List<MailWebSocketEvent.NewMails.Mail> {
    return Emails
        .filterForUserAndFolder(userId, filterFolderId)
        .let {
            if (filterEmailId != null) it.andWhere { Emails.id eq filterEmailId }
            else it
        }
        .orderBy(Emails.sentAt, SortOrder.DESC)
        .let {
            if (limit != null) it.limit(limit)
            else it
        }
        .let {
            if (offset != null) it.offset(offset)
            else it
        }
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

suspend fun sendMailCountToSession(session: RealtimeSubscription, totalMails: Long, fetchedMails: Long) {
    session.session.sendSerialized<MailWebSocketEvent>(MailWebSocketEvent.MetadataChanged(totalMails, fetchedMails))
}

suspend fun pushMailsToSession(session: RealtimeSubscription, mails: List<MailWebSocketEvent.NewMails.Mail>) {
    session.session.sendSerialized<MailWebSocketEvent>(MailWebSocketEvent.NewMails(mails))
}

suspend fun emailChange(emailId: Int) {
    val email = Database.query { Email.findById(emailId)!! }
    val user = Database.query { email.imapConfig.owner }
    val newEmailDto = Database.query { getMailsForUserId(user.id.value, email.folder.id.value, emailId, null, null) }
    RealtimeManager.getSessions(user.id.value, RealtimeSubscriptionType.Mails).forEach { session ->
        pushMailsToSession(session, newEmailDto)
    }
}

fun getEmailCount(userId: Int, folderInt: Int) = Emails
    .filterForUserAndFolder(userId, folderInt)
    .count()

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

    @Serializable
    @SerialName("metadata")
    data class MetadataChanged(
        @SerialName("total_mails") val totalMails: Long,
        @SerialName("fetched_mails") val fetchedMails: Long
    ): MailWebSocketEvent()
}

@Serializable
sealed class MailWebSocketMessage {
    @Serializable
    @SerialName("request_next_chunk")
    object RequestNextChunk: MailWebSocketMessage()
}