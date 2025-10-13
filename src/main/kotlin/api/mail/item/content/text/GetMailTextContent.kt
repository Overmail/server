package dev.babies.overmail.api.mail.item.content.text

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.api.mail.getMail
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.EmailContent
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select

fun Route.getMailTextContent() {
    authenticate(AUTHENTICATION_NAME) {
        get {
            val mail = call.getMail() ?: return@get
            val withCache = call.queryParameters["with_cache"]?.toBoolean() ?: false

            val text = Database.query {
                EmailContent
                    .select(EmailContent.textContent, EmailContent.textSize)
                    .where { (EmailContent.id eq mail.id) and EmailContent.textContent.isNotNull() }
                    .limit(1)
                    .firstOrNull()
                    ?.let { it[EmailContent.textContent]!! to it[EmailContent.textSize] }
            }

            if (text == null) {
                call.respond(HttpStatusCode.NotFound, "No Text content available")
                return@get
            }

            val (stream, size) = text

            if (withCache) call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 86400))

            call.respondOutputStream(
                contentType = ContentType.Text.Plain,
                contentLength = size,
                status = HttpStatusCode.OK
            ) {
                stream.inputStream.copyTo(this)
            }
        }
    }
}