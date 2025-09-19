package dev.babies.overmail.api.auth.form

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.babies.overmail.BASE_URL
import dev.babies.overmail.api.jwtAudience
import dev.babies.overmail.api.jwtIssuer
import dev.babies.overmail.api.jwtSecret
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.User
import dev.babies.overmail.data.model.Users
import dev.babies.overmail.util.sha256
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaInstant

fun Route.formLogin() {
    post {
        val formData = call.receiveParameters()

        val username = formData["username"]

        try {
            username!!
            val password = formData["password"]!!.sha256()

            val user = Database.query {
                User
                    .find { (Users.username eq username) and (Users.password eq password) }
                    .firstOrNull()
            }

            if (user == null) {
                val url = URLBuilder(BASE_URL).apply {
                    appendPathSegments("auth")
                    parameters.append("invalid", "true")
                    parameters.append("username", username)
                }
                call.respondRedirect(url.buildString())
                return@post
            }

            val expires = Clock.System.now() + 24.hours

            val token = JWT
                .create()
                .withAudience(jwtAudience)
                .withIssuer(jwtIssuer)
                .withClaim("id", user.id.value)
                .withExpiresAt(expires.toJavaInstant())
                .sign(Algorithm.HMAC256(jwtSecret))

            call.response.cookies.append(
                name = "overmail-token",
                value = token,
                maxAge = 24 * 60 * 60,
                path = "/",
                httpOnly = true
            )
            call.respondRedirect(BASE_URL)
            return@post

        } catch (e: Exception) {

            val url = URLBuilder(BASE_URL).apply {
                appendPathSegments("auth")
                parameters.append("error", ("Something went wrong: " + e.stackTraceToString()).encodeBase64())
                if (username != null) parameters.append("username", username)
            }

            call.respondRedirect(url.buildString())
        }
    }
}