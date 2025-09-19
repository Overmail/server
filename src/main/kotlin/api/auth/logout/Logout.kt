package dev.babies.overmail.api.auth.logout

import dev.babies.overmail.BASE_URL
import dev.babies.overmail.api.AUTHENTICATION_NAME
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.logout() {
    authenticate(AUTHENTICATION_NAME, strategy = AuthenticationStrategy.Optional) {
        get {
            call.response.cookies.append(
                name = "overmail-token",
                value = "",
                maxAge = 0,
                path = "/",
                httpOnly = true
            )

            call.respondRedirect(BASE_URL)
        }
    }
}