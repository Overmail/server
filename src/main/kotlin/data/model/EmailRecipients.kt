package dev.babies.overmail.data.model

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

class EmailRecipient(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<EmailRecipient>(EmailRecipients)

    var email by Email referencedOn EmailRecipients.email
    var recipient by EmailUser referencedOn EmailRecipients.recipient
    var type by EmailRecipients.type
}

enum class RecipientType {
    To, Cc, Bcc
}

object EmailRecipients : IntIdTable("email_recipients") {
    val email = reference("email", Emails, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val recipient = reference("recipient", EmailUsers, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val type = enumeration("type", RecipientType::class)

    init {
        uniqueIndex(email, recipient, type)
    }
}