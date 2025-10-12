package dev.babies.overmail.api.mail.item.refresh

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.api.mail.getMail
import dev.babies.overmail.mail.daemon.EmailImporter
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.koin.ktor.ext.inject

fun Route.refreshEmail() {
    authenticate(AUTHENTICATION_NAME) {
        post {
            val email = call.getMail() ?: return@post
            val emailImporter by inject<EmailImporter>()
            emailImporter.import(email.id.value)
            call.respond("Ok")
        }
    }
}