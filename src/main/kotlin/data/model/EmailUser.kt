package dev.babies.overmail.data.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

class EmailUser(id: EntityID<Int>) : IntEntity(id) {
    companion object Companion : IntEntityClass<EmailUser>(EmailUsers)

    var displayName by EmailUsers.displayName
    var email by EmailUsers.email
}

object EmailUsers : IntIdTable("email_users") {
    val displayName = varchar("display_name", length = 256)
    val email = varchar("email", length = 256)
}