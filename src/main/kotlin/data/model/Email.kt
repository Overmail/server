package dev.babies.overmail.data.model

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp

class Email(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Email>(Emails)

    var subject by Emails.subject
    var textBody by Emails.textBody
    var htmlBody by Emails.htmlBody
    var isRead by Emails.isRead
    var sentBy by EmailUser via EmailSenders
    val receivedBy by EmailRecipient referrersOn EmailRecipients.email
    var createdAt by Emails.createdAt
    var sentAt by Emails.sentAt
    var emailKey by Emails.emailKey
    var folder by ImapFolder referencedOn Emails.folder
    var imapConfig by ImapConfig referencedOn Emails.imapConfig
    var folderUid by Emails.folderUid
    var rawSource by Emails.rawSource
    var isRemoved by Emails.isRemoved
}

object Emails : IntIdTable("emails") {
    val subject = varchar("subject", length = 1024).nullable()
    val textBody = text("text_body")
    val htmlBody = text("html_body").nullable()
    val isRead = bool("is_read")
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val sentAt = timestamp("sent_at")
    val emailKey = varchar("email_key", length = 1024)
    val imapConfig = reference("imap_config", ImapConfigs, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val folder = reference("folder", ImapFolders, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val folderUid = long("folder_uid")
    val rawSource = text("raw_source")
    val isRemoved = bool("is_removed").default(false)
}