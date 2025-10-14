package dev.babies.overmail.api.webapp.folder

import dev.babies.overmail.api.AUTHENTICATION_NAME
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.Emails
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

fun Route.getFolderMetadata() {
    authenticate(AUTHENTICATION_NAME) {
        get {
            val (folder, _) = call.getFolder() ?: return@get

            Database.query {
                GetMetadataResponse(
                    name = folder.folderName,
                    emailCount = Emails
                        .select(Count(Emails.id))
                        .where {
                            (Emails.folder eq folder.id) and
                                    (Emails.isRemoved eq false)
                        }
                        .first()[Count(Emails.id)]
                        .toInt()
                )
            }.let { call.respond(it) }
        }
    }
}

@Serializable
data class GetMetadataResponse(
    @SerialName("name") val name: String,
    @SerialName("email_count") val emailCount: Int,
)