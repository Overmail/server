package dev.babies.overmail.api.mail.item.content.text

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.api.mail.getMail
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun Route.getMailTextContent() {
    authenticate(AUTHENTICATION_NAME) {
        get {
            val mail = call.getMail() ?: return@get

            call.respond(MailTextContentResponse(mail.textBody))
        }
    }
}

@Serializable
data class MailTextContentResponse(
    @SerialName("text") val text: String
)