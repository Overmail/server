package dev.babies.overmail.api

import dev.babies.overmail.api.auth.check.authCheck
import dev.babies.overmail.api.auth.form.formLogin
import dev.babies.overmail.api.auth.logout.logout
import dev.babies.overmail.api.mail.item.content.html.getMailHtmlContent
import dev.babies.overmail.api.mail.item.content.text.getMailTextContent
import dev.babies.overmail.api.webapp.realtime.folders.foldersWebSocket
import dev.babies.overmail.api.webapp.realtime.mail.mailWebSocket
import dev.babies.overmail.api.webapp.realtime.mails.mailsWebSocket
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        route("/api") {
            route("/auth") {
                route("/check") {
                    authCheck()
                }
                route("/form") {
                    formLogin()
                }
                route("/logout") {
                    logout()
                }
            }

            route("/mail") {
                route("/{mailId}") {
                    route("/content") {
                        route("/html") {
                            getMailHtmlContent()
                        }
                        route("/text") {
                            getMailTextContent()
                        }
                    }
                }
            }

            route("/webapp") {
                route("/realtime") {
                    route("/folder") {
                        foldersWebSocket()
                    }

                    route("/mails") {
                        mailsWebSocket()
                    }

                    route("/mail") {
                        mailWebSocket()
                    }
                }
            }
        }
    }
}