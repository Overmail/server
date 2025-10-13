package dev.babies.overmail.data.model

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp

class ImapConfig(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ImapConfig>(ImapConfigs)
    var host by ImapConfigs.host
    var port by ImapConfigs.port
    var ssl by ImapConfigs.ssl
    var username by ImapConfigs.username
    var password by ImapConfigs.password
    var email by ImapConfigs.email
    var owner by User referencedOn ImapConfigs.owner
    var createdAt by ImapConfigs.createdAt

    val folders by ImapFolder referrersOn ImapFolders.imapConfig
}

object ImapConfigs : IntIdTable("imap_configs") {
    val host = varchar("host", length = 128)
    val port = integer("port")
    val ssl = bool("ssl")
    val username = varchar("username", length = 128)
    val password = varchar("password", length = 128)
    val email = varchar("email", length = 128)
    val owner = reference("owner", Users, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
}