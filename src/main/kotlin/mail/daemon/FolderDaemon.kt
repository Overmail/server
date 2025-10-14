package dev.babies.overmail.mail.daemon

import com.sun.mail.imap.IMAPFolder
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.ImapConfig
import dev.babies.overmail.data.model.ImapFolder
import dev.babies.overmail.data.model.ImapFolders
import jakarta.mail.Store
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import jakarta.mail.Folder as JakartaFolder

typealias FolderId = Int

class FolderDaemon(
    private val imapConfig: ImapConfig,
    private val coroutineScope: CoroutineScope,
    private val imapDaemon: ImapDaemon
) {

    var defaultFolder: JakartaFolder? = null
    private val store: Store = imapDaemon.session.getStore("imap")

    private val idleDaemons = mutableMapOf<ImapConfigId, IdleDaemon>()
    val storeInstances = mutableMapOf<FolderId, StoreInstance>()
    private val importInstances = mutableMapOf<FolderId, StoreInstance>()
    val mailsDaemons = mutableMapOf<FolderId, MailsDaemon>()

    val folderImporterMutex = Mutex()

    init {
        coroutineScope.launch {
            store.connect(imapConfig.username, imapConfig.password)
            defaultFolder = store.defaultFolder

            suspend fun loadAllFolders() {
                store.defaultFolder
                    .list("*")
                    .sortedBy { it.fullName }
                    .forEach { folder ->
                        upsertFolder(folder)
                    }
            }

            while (isActive) {
                folderImporterMutex.withLock {
                    loadAllFolders()
                }
                delay(1.minutes)
            }
        }
    }

    private suspend fun upsertFolder(folder: JakartaFolder) {
        requireNotNull(defaultFolder) { "Default folder is not set" }
        val folderPath = folder.fullName.split(defaultFolder!!.separator)

        val dbFolder = Database.query {
            val existing = ImapFolder
                .find {
                    (ImapFolders.imapConfig eq imapConfig.id.value) and (ImapFolders.folderPath eq folderPath.joinToString(
                        "/"
                    ))
                }
                .firstOrNull()

            if (existing != null) return@query existing

            val parentFolder = if (folderPath.size > 1) {
                Database.query {
                    ImapFolder
                        .find {
                            (ImapFolders.imapConfig eq imapConfig.id.value) and (ImapFolders.folderPath eq folderPath.dropLast(
                                1
                            ).joinToString("/"))
                        }
                        .firstOrNull()
                }
            } else null


            val parentFolderChildrenCount =
                parentFolder?.children?.count() ?: ImapFolder.find { (ImapFolders.imapConfig eq imapConfig.id.value) and (ImapFolders.parentFolder.isNull()) }.count()

            return@query ImapFolder.new {
                this@new.imapConfig = this@FolderDaemon.imapConfig
                this.folderPath = folderPath.joinToString("/").dropWhile { it == defaultFolder!!.separator }
                this.folderName = folder.name
                this.parentFolder = parentFolder
                this.order = (parentFolderChildrenCount + 1) * 100f
            }
        }

        coroutineScope.launch {
            startMailsDaemon(folder.separator, dbFolder)
        }
    }

    fun stop() {
        store.close()
        defaultFolder?.close(false)
        defaultFolder = null
        storeInstances.values.forEach { it.close() }
        idleDaemons.values.forEach { it.stop() }
        mailsDaemons.values.forEach { it.stop() }
    }

    private fun startMailsDaemon(separator: Char, dbFolder: ImapFolder) {
        val dbFolderId = dbFolder.id.value
        val fullFolderName = dbFolder.folderPath.split("/").joinToString(separator.toString())
        run createOrSkipStoreInstance@{
            if (dbFolderId in storeInstances) return@createOrSkipStoreInstance
            storeInstances[dbFolderId] = StoreInstance(
                getStore = {
                    imapDaemon.session.getStore("imap").apply {
                        connect(imapConfig.username, imapConfig.password)
                    }
                },
                defaultFolderName = fullFolderName,
                coroutineScope = coroutineScope
            )
        }

        run createOrSkipImportInstance@{
            if (dbFolderId in importInstances) return@createOrSkipImportInstance
            importInstances[dbFolderId] = StoreInstance(
                getStore = {
                    imapDaemon.session.getStore("imap").apply {
                        connect(imapConfig.username, imapConfig.password)
                    }
                },
                defaultFolderName = fullFolderName,
                coroutineScope = coroutineScope
            )
        }

        run createOrSkipIdleDaemon@{
            if (dbFolderId in idleDaemons) return@createOrSkipIdleDaemon
            if (dbFolder.folderName != "INBOX") return@createOrSkipIdleDaemon
            idleDaemons[dbFolderId] = IdleDaemon(
                fullFolderName = fullFolderName,
                imapDaemon = this.imapDaemon,
                imapConfig = this.imapConfig,
                coroutineScope = coroutineScope,
                onNewMessage = { newMessageUid ->
                    coroutineScope.launch {
                        println("New message: $newMessageUid in ${dbFolder.folderPath}")
                        val mailsDaemon = mailsDaemons[dbFolderId]!!
                        mailsDaemon.upsertMessage(newMessageUid, true)
                    }
                }
            )
        }
        if (mailsDaemons.containsKey(dbFolderId)) return
        val daemon = MailsDaemon(
            dbFolder = dbFolder,
            imapConfig = imapConfig,
            serverFolderName = fullFolderName,
            storeInstance = storeInstances[dbFolderId]!!,
            contentImporterInstance = importInstances[dbFolderId]!!,
            coroutineScope = coroutineScope,
        )
        mailsDaemons[dbFolderId] = daemon
    }

    /**
     * @return A store instance that is very likely to be ready to use.
     */
    fun getStoreInstance(): StoreInstance {
        return storeInstances.values.firstOrNull { it.isReady } ?: storeInstances.values.random()
    }
}

class StoreInstance(
    private val getStore: () -> Store,
    private val coroutineScope: CoroutineScope,
    private val defaultFolderName: String? = null
) {
    private var store: Store? = null
    private var closeJob: Job? = null
    private val mutex = Mutex()
    val isReady: Boolean
        get() = !mutex.isLocked

    suspend fun <T> withStore(block: suspend (store: Store) -> T): T {
        return mutex.withLock {
            closeJob?.cancel()
            if (store?.isConnected != true) store = getStore()
            val result = block(store!!)
            closeJob = coroutineScope.launch {
                delay(30.seconds)
                mutex.withLock {
                    store?.close()
                    store = null
                }
            }.apply {
                invokeOnCompletion { closeJob = null }
            }

            return@withLock result
        }
    }

    enum class FolderMode {
        ReadOnly,
        ReadWrite
    }

    suspend fun <T> withFolder(fullFolderPath: String? = null, folderMode: FolderMode = FolderMode.ReadOnly, block: suspend (folder: IMAPFolder) -> T): T = withStore { store ->
        requireNotNull(fullFolderPath ?: defaultFolderName) { "Folder path is null" }
        val folder = store.getFolder(fullFolderPath ?: defaultFolderName)
        try {
            when (folderMode) {
                FolderMode.ReadOnly -> folder.open(JakartaFolder.READ_ONLY)
                FolderMode.ReadWrite -> folder.open(JakartaFolder.READ_WRITE)
            }
            return@withStore block(folder as IMAPFolder)
        } finally {
            folder.close(false)
        }
    }

    fun close() {
        closeJob?.cancel()
        store?.close()
    }
}