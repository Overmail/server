package dev.babies.overmail.mail.daemon

import com.sun.mail.imap.IMAPFolder
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.ImapConfig
import dev.babies.overmail.data.model.ImapFolder
import dev.babies.overmail.data.model.ImapFolders
import io.ktor.util.logging.*
import jakarta.mail.MessagingException
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

    private val logger = KtorSimpleLogger("FolderDaemon@${imapConfig.host}")
    
    var defaultFolder: JakartaFolder? = null
    private var store: Store? = null

    private val idleDaemons = mutableMapOf<ImapConfigId, IdleDaemon>()
    val storeInstances = mutableMapOf<FolderId, StoreInstance>()
    private val importInstances = mutableMapOf<FolderId, StoreInstance>()
    val mailsDaemons = mutableMapOf<FolderId, MailsDaemon>()

    val folderImporterMutex = Mutex()
    
    @Volatile
    private var isRunning = true
    private var connectionJob: Job? = null

    init {
        connectionJob = coroutineScope.launch {
            connectWithRetry()
        }
    }

    private suspend fun connectWithRetry() {
        var reconnectAttempt = 0
        val maxReconnectDelay = 300 // 5 minutes max

        while (isRunning && isActive) {
            try {
                connectAndLoadFolders()
                reconnectAttempt = 0 // Reset on successful connection
            } catch (e: CancellationException) {
                logger.info("Connection job cancelled")
                throw e
            } catch (e: Exception) {
                if (!isRunning || !isActive) break
                
                val delaySeconds = minOf(5 * (1 shl reconnectAttempt), maxReconnectDelay)
                logger.error("Failed to connect to IMAP store (attempt ${reconnectAttempt + 1}), retrying in ${delaySeconds}s: ${e.message}")
                reconnectAttempt++
                
                delay(delaySeconds.seconds)
            }
        }
    }

    private suspend fun connectAndLoadFolders() {
        try {
            store = imapDaemon.session.getStore("imap")
            store?.connect(imapConfig.username, imapConfig.password)
            defaultFolder = store?.defaultFolder
            logger.info("Connected to IMAP store and loaded default folder")

            suspend fun loadAllFolders() {
                try {
                    store?.defaultFolder
                        ?.list("*")
                        ?.sortedBy { it.fullName }
                        ?.forEach { folder ->
                            upsertFolder(folder)
                        }
                } catch (e: MessagingException) {
                    logger.error("Error loading folders: ${e.message}")
                    throw e
                }
            }

            while (isRunning && isActive && store?.isConnected == true) {
                try {
                    folderImporterMutex.withLock {
                        loadAllFolders()
                    }
                    delay(1.minutes)
                } catch (e: MessagingException) {
                    logger.error("Error during folder sync: ${e.message}")
                    throw e
                }
            }
        } finally {
            cleanupConnection()
        }
    }

    private fun cleanupConnection() {
        try {
            defaultFolder?.close(false)
        } catch (e: Exception) {
            logger.error("Error closing default folder: ${e.message}")
        }
        defaultFolder = null
        
        try {
            store?.close()
        } catch (e: Exception) {
            logger.error("Error closing store: ${e.message}")
        }
        store = null
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
        logger.info("Stopping FolderDaemon")
        isRunning = false
        connectionJob?.cancel()
        
        cleanupConnection()
        
        storeInstances.values.forEach { 
            try {
                it.close()
            } catch (e: Exception) {
                logger.error("Error closing store instance: ${e.message}")
            }
        }
        storeInstances.clear()
        
        importInstances.values.forEach { 
            try {
                it.close()
            } catch (e: Exception) {
                logger.error("Error closing import instance: ${e.message}")
            }
        }
        importInstances.clear()
        
        idleDaemons.values.forEach { 
            try {
                it.stop()
            } catch (e: Exception) {
                logger.error("Error stopping idle daemon: ${e.message}")
            }
        }
        idleDaemons.clear()
        
        mailsDaemons.values.forEach { 
            try {
                it.stop()
            } catch (e: Exception) {
                logger.error("Error stopping mails daemon: ${e.message}")
            }
        }
        mailsDaemons.clear()
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
                        logger.info("New message: $newMessageUid in ${dbFolder.folderPath}")
                        val mailsDaemon = mailsDaemons[dbFolderId]
                        if (mailsDaemon != null) {
                            try {
                                mailsDaemon.upsertMessage(newMessageUid)
                            } catch (e: Exception) {
                                logger.error("Error upserting message: ${e.message}")
                            }
                        } else {
                            logger.warn("MailsDaemon not found for folder $dbFolderId")
                        }
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
    private val logger = KtorSimpleLogger("StoreInstance@${defaultFolderName ?: "default"}")
    private var store: Store? = null
    private var closeJob: Job? = null
    private val mutex = Mutex()
    val isReady: Boolean
        get() = !mutex.isLocked

    suspend fun <T> withStore(block: suspend (store: Store) -> T): T {
        return mutex.withLock {
            closeJob?.cancel()
            
            // Check if store is connected, if not, create new one
            if (store?.isConnected != true) {
                try {
                    store?.close()
                } catch (e: Exception) {
                    logger.debug("Error closing old store: ${e.message}")
                }
                
                try {
                    store = getStore()
                    logger.debug("Created new store connection")
                } catch (e: Exception) {
                    logger.error("Failed to create store: ${e.message}")
                    throw e
                }
            }
            
            val result = try {
                block(store!!)
            } catch (e: MessagingException) {
                logger.error("Messaging exception in withStore: ${e.message}")
                // Mark store as invalid for next use
                try {
                    store?.close()
                } catch (closeEx: Exception) {
                    logger.debug("Error closing store after exception: ${closeEx.message}")
                }
                store = null
                throw e
            }
            
            closeJob = coroutineScope.launch {
                delay(30.seconds)
                mutex.withLock {
                    try {
                        store?.close()
                        logger.debug("Closed idle store connection")
                    } catch (e: Exception) {
                        logger.debug("Error closing store: ${e.message}")
                    }
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
        } catch (e: Exception) {
            logger.error("Error in withFolder: ${e.message}")
            throw e
        } finally {
            try {
                folder.close(false)
            } catch (e: Exception) {
                logger.debug("Error closing folder: ${e.message}")
            }
        }
    }

    fun close() {
        closeJob?.cancel()
        try {
            store?.close()
            logger.debug("Closed store in close()")
        } catch (e: Exception) {
            logger.debug("Error in close(): ${e.message}")
        }
        store = null
    }
}