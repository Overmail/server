package dev.babies.overmail.api.mail.item.content.html

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

fun Route.getMailHtmlContent() {
    authenticate(AUTHENTICATION_NAME) {
        get {
            val mail = call.getMail() ?: return@get
            val withCache = call.queryParameters["with_cache"]?.toBoolean() ?: false

            val html = Database.query {
                EmailContent
                    .select(EmailContent.htmlContent, EmailContent.htmlSize)
                    .where { (EmailContent.id eq mail.id) and EmailContent.htmlContent.isNotNull() }
                    .limit(1)
                    .firstOrNull()
                    ?.let { it[EmailContent.htmlContent]!! to it[EmailContent.htmlSize] }
            }

            if (html == null) {
                call.respond(HttpStatusCode.NotFound, "No HTML content available")
                return@get
            }

            val (stream, size) = html

            if (withCache) call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 86400))

            call.respondOutputStream(
                contentType = ContentType.Text.Html,
                contentLength = size?.plus(JS_INSET.length),
                status = HttpStatusCode.OK
            ) {
                stream.inputStream.copyTo(this)
                this.write(JS_INSET.toByteArray())
            }
        }
    }
}

private const val JS_INSET = """
<script>
  const links = document.getElementsByTagName('a');
  for (let i = 0; i < links.length; i++) {
    links[i].setAttribute('target', '_blank');
    links[i].setAttribute('rel', 'noopener noreferrer');
  }
  
  function notifyResize() {
    const height = Math.max(
      document.body.scrollHeight,
      document.body.offsetHeight
    );
    parent.postMessage({ type: 'resize', height }, '*');
  }
            
  window.addEventListener('load', notifyResize);
  new MutationObserver(notifyResize).observe(document.body, {
    childList: true, subtree: true, attributes: true
  });
</script>
"""