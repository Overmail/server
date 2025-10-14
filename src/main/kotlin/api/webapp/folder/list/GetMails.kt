package dev.babies.overmail.api.webapp.folder.list

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.api.webapp.folder.getFolder
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.Email
import dev.babies.overmail.data.model.Emails
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

fun Route.getMails() {
    authenticate(AUTHENTICATION_NAME) {
        get {
            val (folder, _) = call.getFolder() ?: return@get

            Database.query {
                val mails = Emails
                    .selectAll()
                    .where { (Emails.folder eq folder.id) and (Emails.isRemoved eq false) }
                    .orderBy(Emails.sentAt, order = SortOrder.DESC)
                    .map { Email.wrapRow(it) }

                return@query GetMailsResponse(
                    messages = mails.map { mail ->
                        GetMailsResponse.Message(
                            id = mail.id.value.toString(),
                            subject = mail.subject,
                            sentAt = mail.sentAt.epochSeconds,
                            from = mail.sentBy.joinToString { it.displayName },
                            isRead = mail.isRead,
                        )
                    }
                )
            }.let {
                call.respond(it)
            }
        }
    }
}

@Serializable
data class GetMailsResponse(
    @SerialName("messages") val messages: List<Message>
) {
    @Serializable
    data class Message(
        @SerialName("id") val id: String,
        @SerialName("subject") val subject: String?,
        @SerialName("from") val from: String,
        @SerialName("sent_at") val sentAt: Long,
        @SerialName("is_read") val isRead: Boolean,
    )
}