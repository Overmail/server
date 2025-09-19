package dev.babies.overmail.data.model

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp

class User(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<User>(Users)

    var username by Users.username
    var password by Users.password
    var createdAt by Users.createdAt
}

object Users : IntIdTable(name = "users") {
    val username = varchar("username", length = 64)
    val password = varchar("password", length = 64)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    init {
        uniqueIndex(username)
    }
}