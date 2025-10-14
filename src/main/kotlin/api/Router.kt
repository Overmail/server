package dev.babies.overmail.api

import dev.babies.overmail.api.auth.check.authCheck
import dev.babies.overmail.api.auth.form.formLogin
import dev.babies.overmail.api.auth.logout.logout
import dev.babies.overmail.api.mail.item.content.html.getMailHtmlContent
import dev.babies.overmail.api.mail.item.content.text.getMailTextContent
import dev.babies.overmail.api.mail.item.read.setReadState
import dev.babies.overmail.api.webapp.email.getEmailMetadata
import dev.babies.overmail.api.webapp.email.moveEmail
import dev.babies.overmail.api.mail.item.reloadEmail
import dev.babies.overmail.api.webapp.folder.getFolderMetadata
import dev.babies.overmail.api.webapp.folder.list.getMails
import dev.babies.overmail.api.webapp.sidebar.folders.sidebarFolders
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

            route("/mails") {
                route("/{mailId}") {
                    route("/content") {
                        route("/html") {
                            getMailHtmlContent()
                        }
                        route("/text") {
                            getMailTextContent()
                        }
                    }
                    route("/move") {
                        moveEmail()
                    }
                    route("/read") {
                        setReadState()
                    }
                    route("/reload") {
                        reloadEmail()
                    }
                }
            }

            route("/webapp") {
                route("/sidebar") {
                    route("/folders") {
                        sidebarFolders()
                    }
                }

                route("/folder") {
                    route("/{folderId}") {
                        getFolderMetadata()

                        route("/list") {
                            getMails()
                        }
                    }
                }

                route("/email") {
                    route("/{mailId}") {
                        getEmailMetadata()
                    }
                }
            }
        }
    }
}