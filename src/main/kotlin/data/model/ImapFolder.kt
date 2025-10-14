package dev.babies.overmail.data.model

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

class ImapFolder(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ImapFolder>(ImapFolders)

    var imapConfig by ImapConfig referencedOn ImapFolders.imapConfig
    var folderName by ImapFolders.folderName
    var parentFolder by ImapFolder optionalReferencedOn ImapFolders.parentFolder
    var folderPath by ImapFolders.folderPath
    val children by ImapFolder optionalReferrersOn ImapFolders.parentFolder
    val emails by Email referrersOn Emails.folder
    var order by ImapFolders.order

    fun getPath(): List<String> {
        if (parentFolder == null) return listOf(folderName)
        if (parentFolder?.parentFolder == null) return listOf(folderName)
        return parentFolder?.getPath().orEmpty() + folderName
    }
}

object ImapFolders : IntIdTable("imap_folder") {
    val imapConfig = reference("imap_config", ImapConfigs, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE)
    val folderName = varchar("folder_name", length = 128)
    val folderPath = varchar("folder_path", length = 1024)
    val parentFolder = reference("parent_folder", ImapFolders, onDelete = ReferenceOption.CASCADE, onUpdate = ReferenceOption.CASCADE).nullable()
    val order = float("order")

    init {
        uniqueIndex(imapConfig, folderName, parentFolder)
    }
}