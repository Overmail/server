package dev.babies.overmail.api.webapp.realtime.mail

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.api.webapp.realtime.RealtimeManager
import dev.babies.overmail.api.webapp.realtime.RealtimeSubscription
import dev.babies.overmail.api.webapp.realtime.mails.MailWebSocketMessage
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.Email
import dev.babies.overmail.data.model.User
import dev.babies.overmail.json
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
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
                } finally {
                    RealtimeManager.removeSession(user.id.value, session)
                }
            }
        }
    }
}

suspend fun pushMailToSession(session: RealtimeSubscription.MailSubscription, mail: Email) {
    session.session.sendSerialized<MailWebSocketEvent>(MailWebSocketEvent.MetadataChanged(
        subject = mail.subject,
        sentAt = mail.sentAt.epochSeconds,
        sentBy = Database.query { mail.sentBy.joinToString { it.displayName } },
        hasHtmlBody = mail.htmlBody != null,
        isRead = mail.isRead,
    ))
}

@Serializable
sealed class MailWebSocketEvent {
        @Serializable
    @SerialName("metadata")
    data class MetadataChanged(
        @SerialName("subject") val subject: String?,
        @SerialName("sent_at") val sentAt: Long,
        @SerialName("sent_by") val sentBy: String,
        @SerialName("has_html_body") val hasHtmlBody: Boolean,
        @SerialName("is_read") val isRead: Boolean,
    ): MailWebSocketEvent()
}