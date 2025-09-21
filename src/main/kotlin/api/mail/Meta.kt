package dev.babies.overmail.api.mail

import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.Email
import dev.babies.overmail.data.model.User
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respondText
import kotlin.text.toInt

suspend fun ApplicationCall.getMail(): Email? {
    val mailId = this.parameters["mailId"]?.toInt()!!
    val user = this.principal<JWTPrincipal>()!!.payload.getClaim("id").asInt().let {
        Database.query { User.findById(it)!! }
    }

    val mail = Database.query { Email.findById(mailId) }
    if (mail == null) {
        this.respondText("Mail not found")
        return@getMail null
    }

    if (Database.query { mail.imapConfig.owner.id.value != user.id.value }) {
        this.respondText("You don't have access to this mail")
        return@getMail null
    }

    return mail
}