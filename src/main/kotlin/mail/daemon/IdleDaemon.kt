package dev.babies.overmail.mail.daemon

import com.sun.mail.imap.IMAPFolder
import dev.babies.overmail.data.model.ImapConfig
import io.ktor.util.logging.*
import jakarta.mail.FolderClosedException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Store
import jakarta.mail.event.MessageChangedEvent
import jakarta.mail.event.MessageChangedListener
import jakarta.mail.event.MessageCountEvent
import jakarta.mail.event.MessageCountListener
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds

class IdleDaemon(
    private val fullFolderName: String,
    private val imapDaemon: ImapDaemon,
    private val imapConfig: ImapConfig,
    private val onNewMessage: (messageUid: Long) -> Unit,
    private val coroutineScope: CoroutineScope,
) {

    private val logger = KtorSimpleLogger("${fullFolderName}/IdleDaemon@${imapConfig.host}")
    @Volatile
    private var isRunning = true
    private var idleJob: Job? = null

    init {
        logger.info("Starting idle daemon")
        idleJob = coroutineScope.launch { runIdleLoop() }
    }

    private suspend fun runIdleLoop() {
        var reconnectAttempt = 0
        val maxReconnectDelay = 300 // 5 minutes max delay

        while (isRunning && coroutineScope.isActive) {
            try {
                runIdleStore()
                reconnectAttempt = 0 // Reset on successful connection
            } catch (e: Exception) {
                if (!isRunning || !coroutineScope.isActive) break
                
                val delaySeconds = minOf(5 * (1 shl reconnectAttempt), maxReconnectDelay)
                logger.error("Idle daemon connection failed (attempt ${reconnectAttempt + 1}), reconnecting in ${delaySeconds}s: ${e.message}")
                reconnectAttempt++
                
                delay(delaySeconds.seconds)
            }
        }
        logger.info("Idle daemon loop stopped")
    }

    private suspend fun runIdleStore() {
        var store: Store? = null
        var folder: IMAPFolder? = null

        try {
            store = imapDaemon.session.getStore("imap")
            store.connect(imapConfig.username, imapConfig.password)
            logger.info("Connected to IMAP store")

            folder = store.getFolder(fullFolderName) as IMAPFolder
            folder.open(IMAPFolder.READ_ONLY)
            logger.info("Opened folder: $fullFolderName")

            var canIdle = CompletableDeferred(true)

            val messageChangedListener = object : MessageChangedListener {
                override fun messageChanged(e: MessageChangedEvent?) {
                    if (e == null) return
                    try {
                        logger.debug("Message changed in ${folder.fullName}: ${e.message.messageNumber}")
                        onNewMessage(folder.getUID(e.message as Message))
                    } catch (ex: Exception) {
                        logger.error("Error handling message change: ${ex.message}")
                    }
                }
            }

            val messageCountListener = object : MessageCountListener {
                override fun messagesAdded(e: MessageCountEvent?) {
                    if (e == null) return
                    try {
                        e.messages.forEach { message ->
                            onNewMessage(folder.getUID(message as Message))
                        }
                        canIdle.complete(true)
                    } catch (ex: Exception) {
                        logger.error("Error handling new messages: ${ex.message}")
                    }
                }

                override fun messagesRemoved(e: MessageCountEvent?) {
                    if (e == null) return
                    logger.debug("Message removed in ${folder.fullName}: ${e.messages.map { it.messageNumber }}")
                }
            }

            folder.addMessageChangedListener(messageChangedListener)
            folder.addMessageCountListener(messageCountListener)

            while (isRunning && coroutineScope.isActive && store.isConnected && folder.isOpen) {
                try {
                    canIdle.await()
                    canIdle = CompletableDeferred(false)
                    folder.idle()
                    folder.messageCount // Verify connection is still alive
                    delay(1.seconds)
                } catch (e: FolderClosedException) {
                    logger.warn("Folder closed unexpectedly, will reconnect")
                    throw e
                } catch (e: MessagingException) {
                    logger.warn("Messaging exception during idle: ${e.message}")
                    throw e
                }
                delay(1.seconds)
            }
        } catch (e: CancellationException) {
            logger.info("Idle daemon cancelled")
            throw e
        } catch (e: Exception) {
            logger.error("Error in idle daemon: ${e.javaClass.simpleName} - ${e.message}")
            throw e
        } finally {
            try {
                folder?.close(false)
            } catch (e: Exception) {
                logger.error("Error closing folder: ${e.message}")
            }
            try {
                store?.close()
            } catch (e: Exception) {
                logger.error("Error closing store: ${e.message}")
            }
        }
    }

    fun stop() {
        logger.info("Stopping idle daemon")
        isRunning = false
        idleJob?.cancel()
    }
}