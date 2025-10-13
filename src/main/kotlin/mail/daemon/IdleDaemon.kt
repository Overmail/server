package dev.babies.overmail.mail.daemon

import com.sun.mail.imap.IMAPFolder
import dev.babies.overmail.data.model.ImapConfig
import io.ktor.util.logging.*
import jakarta.mail.FolderClosedException
import jakarta.mail.Message
import jakarta.mail.event.MessageChangedEvent
import jakarta.mail.event.MessageChangedListener
import jakarta.mail.event.MessageCountEvent
import jakarta.mail.event.MessageCountListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class IdleDaemon(
    private val fullFolderName: String,
    private val imapDaemon: ImapDaemon,
    private val imapConfig: ImapConfig,
    private val onNewMessage: (messageUid: Long) -> Unit,
    coroutineScope: CoroutineScope,
) {

    private val logger = KtorSimpleLogger("${fullFolderName}/IdleDaemon@${imapConfig.host}")

    init {
        logger.info("Starting idle daemon")
        coroutineScope.launch { runIdleStore() }
    }

    private suspend fun runIdleStore() {
        val store = imapDaemon.session.getStore("imap").apply {
            connect(imapConfig.username, imapConfig.password)
        }

        try {
            var canIdle = CompletableDeferred(true)
            val folder = store.getFolder(fullFolderName) as IMAPFolder
            try {
                folder.open(IMAPFolder.READ_ONLY)
                folder.addMessageChangedListener(object : MessageChangedListener {
                    override fun messageChanged(e: MessageChangedEvent?) {
                        if (e == null) return
                        println("Message changed in ${folder.fullName}: - ${e.message.messageNumber}")
                        onNewMessage(folder.getUID(e.message as Message))
                    }
                })
                folder.addMessageCountListener(object : MessageCountListener {
                    override fun messagesAdded(e: MessageCountEvent?) {
                        if (e == null) return
                        e.messages.forEach {
                            message -> onNewMessage(folder.getUID(message as Message))
                        }

                        canIdle.complete(true)
                    }

                    override fun messagesRemoved(e: MessageCountEvent?) {
                        if (e == null) return
                        println("Message removed in ${folder.fullName}: ${e.messages.map { it.messageNumber }}")
                    }
                })
                while (store.isConnected && folder.isOpen) {
                    try {
                        canIdle.await()
                        canIdle = CompletableDeferred(false)
                        folder.idle()
                        folder.messageCount
                        delay(1.seconds)
                    } catch (_: FolderClosedException) {
                        delay(10.seconds)
                        folder.close(false)
                        store.close()
                        runIdleStore()
                        return
                    }
                    delay(1.seconds)
                }
            } finally {
                folder.close(false)
            }
        } finally {
            store.close()
        }
    }

    fun stop() {

    }
}