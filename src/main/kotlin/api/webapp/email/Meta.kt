package dev.babies.overmail.api.webapp.email

import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.Email
import dev.babies.overmail.data.model.User
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

suspend fun ApplicationCall.getEmail(): Pair<Email, User>? {
    val principal = this.principal<JWTPrincipal>()!!
    val userId = principal.payload.getClaim("id").asInt()
    return Database.query {
        val user = User.findById(userId)!!
        val emailId = this.parameters["mailId"]?.toInt()!!
        val email = Email.findById(emailId) ?: run {
            this.respond(status = HttpStatusCode.NotFound, "Email not found")
            return@query null
        }

        if (email.imapConfig.owner.id.value != user.id.value) {
            this.respond(status = HttpStatusCode.Forbidden, "You don't have access to this email")
            return@query null
        }

        return@query email to user
    }
}