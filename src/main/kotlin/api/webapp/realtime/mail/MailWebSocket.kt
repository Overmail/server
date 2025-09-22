package dev.babies.overmail.api.webapp.realtime.mail

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.api.webapp.realtime.RealtimeManager
import dev.babies.overmail.api.webapp.realtime.RealtimeSubscription
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.Email
import dev.babies.overmail.data.model.RecipientType
import dev.babies.overmail.data.model.User
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun Route.mailWebSocket() {
    authenticate(AUTHENTICATION_NAME) {
        route("/{mailId}") {
            webSocket {
                val principal = this.call.principal<JWTPrincipal>()!!
                val userId = principal.payload.getClaim("id").asInt()
                val user = Database.query { User.findById(userId)!! }
                val mailId = this.call.parameters["mailId"]?.toInt()!!

                val mail = Database.query { Email.findById(mailId) }
                if (mail == null) {
                    send("Mail not found")
                    return@webSocket
                }

                if (Database.query { mail.imapConfig.owner.id.value != userId }) {
                    send("You don't have access to this mail")
                    return@webSocket
                }

                val session = RealtimeSubscription.MailSubscription(
                    userId = user.id.value,
                    emailId = mailId,
                    session = this
                )

                RealtimeManager.addSession(
                    userId = user.id.value,
                    session = session
                )

                try {
                    pushMailToSession(session, mail)
                    for (frame in incoming) {
                        frame as? Frame.Text ?: continue
                    }
                } catch (_: IOException) {
                } finally {
                    RealtimeManager.removeSession(user.id.value, session)
                }
            }
        }
    }
}

suspend fun pushMailToSession(session: RealtimeSubscription.MailSubscription, mail: Email) {
    session.session.sendSerialized<MailWebSocketEvent>(
        MailWebSocketEvent.MetadataChanged(
            subject = mail.subject,
            sentAt = mail.sentAt.epochSeconds,
            sentBy = Database.query { mail.sentBy.joinToString { it.displayName } },
            sentTo = Database.query {
                mail.receivedBy.map {
                    MailWebSocketEvent.MetadataChanged.Recipient(
                        name = it.recipient.displayName,
                        email = it.recipient.email,
                        isMe = it.recipient.email == mail.imapConfig.email,
                        type = MailWebSocketEvent.MetadataChanged.Recipient.typeToType(it.type)
                    )
                }
            },
            hasHtmlBody = mail.htmlBody != null,
            isRead = mail.isRead,
        )
    )
}

suspend fun sendMailDeletedToSession(session: RealtimeSubscription.MailSubscription) {
    session.session.sendSerialized<MailWebSocketEvent>(MailWebSocketEvent.Deleted)
}

@Serializable
sealed class MailWebSocketEvent {
    @Serializable
    @SerialName("metadata")
    data class MetadataChanged(
        @SerialName("subject") val subject: String?,
        @SerialName("sent_at") val sentAt: Long,
        @SerialName("sent_by") val sentBy: String,
        @SerialName("sent_to") val sentTo: List<Recipient>,
        @SerialName("has_html_body") val hasHtmlBody: Boolean,
        @SerialName("is_read") val isRead: Boolean,
    ) : MailWebSocketEvent() {

        @Serializable
        data class Recipient(
            @SerialName("name") val name: String,
            @SerialName("email") val email: String,
            @SerialName("is_me") val isMe: Boolean,
            @SerialName("type") val type: Type,
        ) {

            @Serializable
            enum class Type {
                @SerialName("to")
                To,
                @SerialName("cc")
                Cc,
                @SerialName("bcc")
                Bcc
            }

            companion object {
                fun typeToType(type: RecipientType) = when (type) {
                    RecipientType.To -> Type.To
                    RecipientType.Cc -> Type.Cc
                    RecipientType.Bcc -> Type.Bcc
                }
            }
        }
    }

    @Serializable
    @SerialName("deleted")
    object Deleted : MailWebSocketEvent()
}