package dev.babies.overmail

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json


val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
    classDiscriminator = "type"
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(json)
    }
}
