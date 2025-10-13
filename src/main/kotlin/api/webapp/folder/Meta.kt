package dev.babies.overmail.api.webapp.folder

import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.ImapFolder
import dev.babies.overmail.data.model.User
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

suspend fun ApplicationCall.getFolder(): Pair<ImapFolder, User>? {
    val principal = this.principal<JWTPrincipal>()!!
    val userId = principal.payload.getClaim("id").asInt()
    return Database.query {
        val user = User.findById(userId)!!
        val folderId = this.parameters["folderId"]?.toInt()!!
        val folder = ImapFolder.findById(folderId) ?: run {
            this.respond(status = HttpStatusCode.NotFound, "Folder not found")
            return@query null
        }

        if (folder.imapConfig.owner.id.value != user.id.value) {
            this.respond(status = HttpStatusCode.Forbidden, "You don't have access to this folder")
            return@query null
        }

        return@query folder to user
    }
}