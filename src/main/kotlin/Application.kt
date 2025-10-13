package dev.babies.overmail

import dev.babies.overmail.api.configureAuthentication
import dev.babies.overmail.api.configureRouting
import dev.babies.overmail.api.configureWebSockets
import dev.babies.overmail.data.Database
import dev.babies.overmail.mail.daemon.DaemonManagerPlugin
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.runBlocking

val BASE_URL = System.getenv("BASE_URL") ?: "http://localhost"

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    runBlocking {
        Database.init()
    }
    configureSerialization()
    configureWebSockets()
    configureAuthentication()
    configureRouting()

    install(DaemonManagerPlugin)
}
