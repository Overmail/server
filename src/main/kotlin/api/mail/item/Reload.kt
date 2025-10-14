package dev.babies.overmail.api.mail.item

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.api.webapp.email.getEmail
import dev.babies.overmail.data.Database
import dev.babies.overmail.mail.daemon.DaemonManagerPlugin
import dev.babies.overmail.mail.daemon.ImportRequest
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.reloadEmail() {
    authenticate(AUTHENTICATION_NAME) {
        get {
            val (email, _) = call.getEmail() ?: return@get

            val imapConfig = Database.query { email.imapConfig }
            val folder = Database.query { email.folder }

            val imapDaemon = DaemonManagerPlugin.getDaemonForImapConfig(imapConfig.id.value) ?: throw IllegalStateException("No IMAP daemon for config ${imapConfig.id.value}")
            val mailDaemon = imapDaemon.folderDaemon.mailsDaemons[folder.id.value] ?: throw IllegalStateException("No mail daemon for folder ${folder.id.value}")

            mailDaemon.pendingMessages.send(ImportRequest(emailId = email.id.value, forceUpdate = true))

            call.respond("Ok")
        }
    }
}