package dev.babies.overmail.api.webapp.email

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.EmailContent
import dev.babies.overmail.data.model.RecipientType
import io.ktor.server.auth.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

fun Route.getEmailMetadata() {
    authenticate(AUTHENTICATION_NAME) {
        get {
            val (email, _) = call.getEmail() ?: return@get

            Database.query {
                val contentData = EmailContent
                    .select(EmailContent.textSize, EmailContent.htmlSize)
                    .where { EmailContent.id eq email.id }
                    .firstOrNull()
                    ?.let { it[EmailContent.textSize] to it[EmailContent.htmlSize] }
                EmailMetadataResponse(
                    id = email.id.value.toString(),
                    subject = email.subject,
                    isRead = email.isRead,
                    hasHtml = (contentData?.second ?: 0) > 0,
                    hasText = (contentData?.first ?: 0) > 0,
                    from = email.sentBy.joinToString { it.displayName },
                    recipients = email.receivedBy.map { recipient ->
                        EmailMetadataResponse.Recipient(
                            name = recipient.recipient.displayName,
                            email = recipient.recipient.email,
                            type = when (recipient.type) {
                                RecipientType.To -> EmailMetadataResponse.Recipient.Type.To
                                RecipientType.Cc -> EmailMetadataResponse.Recipient.Type.Cc
                                RecipientType.Bcc -> EmailMetadataResponse.Recipient.Type.Bcc
                            },
                            isMe = recipient.recipient.email == email.imapConfig.email
                        )
                    },
                    sentAt = email.sentAt.epochSeconds,
                )
            }.let { call.respond(it) }
        }
    }
}

@Serializable
data class EmailMetadataResponse(
    @SerialName("id") val id: String,
    @SerialName("subject") val subject: String?,
    @SerialName("is_read") val isRead: Boolean,
    @SerialName("has_html") val hasHtml: Boolean,
    @SerialName("has_text") val hasText: Boolean,
    @SerialName("sent_by") val from: String,
    @SerialName("sent_to") val recipients: List<Recipient>,
    @SerialName("sent_at") val sentAt: Long
) {
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
    }
}