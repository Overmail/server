package dev.babies.overmail.di

import dev.babies.overmail.mail.daemon.EmailImporter
import io.ktor.server.application.*
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.configureKoin() {
    install(Koin) {
        slf4jLogger()
        modules(appModule)
    }
}

private val appModule = module(createdAtStart = true) {
    single { EmailImporter() }
}