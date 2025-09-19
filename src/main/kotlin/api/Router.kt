package dev.babies.overmail.api

import dev.babies.overmail.api.auth.check.authCheck
import dev.babies.overmail.api.auth.form.formLogin
import dev.babies.overmail.api.auth.logout.logout
import io.ktor.server.application.Application
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
        }
    }
}