package dev.babies.overmail.mail.daemon

import com.sun.mail.imap.DefaultFolder
import com.sun.mail.imap.IMAPFolder
import dev.babies.overmail.api.webapp.realtime.folders.notifyFolderChange
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.ImapConfig
import dev.babies.overmail.data.model.ImapFolder
import dev.babies.overmail.data.model.ImapFolders
import dev.babies.overmail.util.forEachAsync
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.slf4j.LoggerFactory

class ImapFolderManager(
    private val imapConfig: ImapConfig,
    private val connectionFactory: ImapConfigurationManager.ConnectionFactory
) {

    private val imapFolderSynchronizers = mutableMapOf<Int, ImapFolderSynchronizer>()
    private val logger = LoggerFactory.getLogger("${imapConfig.email}/ImapFolderManager")

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun start() {
        withContext(Dispatchers.IO) {
            val connection = connectionFactory()
            connection.use {  connection ->
                val defaultFolderImap = connection.defaultFolder as DefaultFolder
                val defaultFolderDatabase = Database.query {
                    ImapFolder
                        .find { (ImapFolders.imapConfig eq imapConfig.id.value) and (ImapFolders.folderName eq defaultFolderImap.name) }
                        .firstOrNull()
                        ?: ImapFolder.new {
                            this.imapConfig = this@ImapFolderManager.imapConfig
                            this.folderName = defaultFolderImap.name
                            this.parentFolder = null
                        }
                }

                recursiveInitFolders(defaultFolderImap, defaultFolderDatabase)

                Database.query {
                    val folders = ImapFolder.find { (ImapFolders.imapConfig eq imapConfig.id.value) and (ImapFolders.folderName neq defaultFolderImap.name) }
                    folders.forEach { folder ->
                        val synchronizer = ImapFolderSynchronizer(imapConfig, folder, connectionFactory)
                        imapFolderSynchronizers[folder.id.value] = synchronizer
                    }
                }
            }

            imapFolderSynchronizers.values.forEach { it.start() }
        }
    }

    private fun recursiveInitFolders(parentImap: IMAPFolder, parentDatabase: ImapFolder) {
        val folders = parentImap.list("%").map { it as IMAPFolder }
        folders.forEach { folder ->
            val folderName = folder.name

            var hasChanges = false

            val dbFolder = Database.query {
                ImapFolder
                    .find { (ImapFolders.imapConfig eq imapConfig.id.value) and (ImapFolders.folderName eq folderName) }
                    .firstOrNull()
                    ?.apply {
                        if (this@apply.folderName != folderName) {
                            this@apply.folderName = folderName
                            hasChanges = true
                        }
                    }
                    ?: ImapFolder.new {
                        this.imapConfig = this@ImapFolderManager.imapConfig
                        this.folderName = folderName
                        this.parentFolder = parentDatabase
                    }.also {
                        logger.info("Created folder ${it.folderName} with id ${it.id.value}")
                    }
            }

            if (hasChanges) scope.launch {
                notifyFolderChange(dbFolder.id.value)
            }

            recursiveInitFolders(folder, dbFolder)
        }
    }

    suspend fun stop() {
        withContext(scope.coroutineContext) {
            imapFolderSynchronizers.values.forEachAsync {
                it.stop()
            }
        }
    }
}