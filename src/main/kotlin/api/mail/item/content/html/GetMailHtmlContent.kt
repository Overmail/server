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

            val html = mail.htmlBody?.plus("\n" + JS_INSET)

            if (rawHtml) {
                if (html != null) call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 86400))
                call.respondText(html.orEmpty(), ContentType.Text.Html)
            }
            else call.respond(MailHtmlContentResponse(html))
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

@Serializable
data class MailHtmlContentResponse(
    @SerialName("html") val html: String?
)