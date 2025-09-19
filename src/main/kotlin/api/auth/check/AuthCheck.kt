package dev.babies.overmail.api.auth.check

import dev.babies.overmail.api.AUTHENTICATION_NAME
import io.ktor.server.auth.authenticate
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authCheck() {
    authenticate(AUTHENTICATION_NAME) {
        get {
            call.respond("Ok")
        }
    }
}