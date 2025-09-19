package dev.babies.overmail.data.model

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

object EmailSenders : IntIdTable("email_sender") {
    val email = reference("email", Emails, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val sender = reference("sender", EmailUsers, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)

    init {
        uniqueIndex(email, sender)
    }
}