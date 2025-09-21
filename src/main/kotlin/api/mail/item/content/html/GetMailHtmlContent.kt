package dev.babies.overmail.api.mail.item.content.html

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.api.mail.getMail
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun Route.getMailHtmlContent() {
    authenticate(AUTHENTICATION_NAME) {
        get {
            val mail = call.getMail() ?: return@get
            val rawHtml = call.queryParameters["raw"]?.toBoolean() ?: false

            if (rawHtml) {
                if (mail.htmlBody != null) call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 86400))
                call.respondText(mail.htmlBody.orEmpty(), ContentType.Text.Html)
            }
            else call.respond(MailHtmlContentResponse(mail.htmlBody))
        }
    }
}

@Serializable
data class MailHtmlContentResponse(
    @SerialName("html") val html: String?
)