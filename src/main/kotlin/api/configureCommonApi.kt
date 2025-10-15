package dev.babies.overmail.api

import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.Users
import io.ktor.server.application.Application
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.jdbc.select
import plus.vplan.commonapi.installCommonApi
import kotlin.time.Clock

fun Application.configureCommonApi() {
    installCommonApi(
        serviceName = "Overmail Backend",
        apiPrefix = "/api",
        defaultText = {
            +"Overmail Backend Service"
        },
        healthCheck = {
            val response = StringBuilder()
            response.appendLine("Overmail Backend Service Healthcheck at ${Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())}")
            Database.query {
                Users
                    .select(Count(Users.id))
                    .firstOrNull()
                    ?.let {
                        response.appendLine("User count: ${it[Count(Users.id)]}")
                    }
            }

            response.appendLine("Healthcheck - OK")
            return@installCommonApi response.toString()
        }
    )
}