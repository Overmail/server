package dev.babies.overmail.mail.daemon

import com.sun.mail.imap.IMAPStore
import dev.babies.overmail.data.model.ImapConfig
import jakarta.mail.Session
import org.slf4j.LoggerFactory
import java.util.*

class ImapConfigurationManager(
    private val imapConfig: ImapConfig
) {

    typealias ConnectionFactory = () -> IMAPStore

    private val isDebug = false

    private fun createConnection(): IMAPStore {
        val props = Properties().apply {
            if (imapConfig.ssl) put("mail.store.protocol", "imaps")
            // Ensure IDLE returns periodically on both imap and imaps
            put("mail.imap.idletimeout", "600000")
            put("mail.imaps.idletimeout", "600000")
            // Allow enough concurrent folder connections for per-folder IDLE
            put("mail.imap.connectionpoolsize", 500)
            put("mail.imaps.connectionpoolsize", 500)
            // Be conservative with idle pooled connections
            put("mail.imap.connectionpooltimeout", "60000")
            put("mail.imaps.connectionpooltimeout", "60000")

            // enable debug logs
            if (isDebug) put("mail.debug", "true")
        }

        val logger = LoggerFactory.getLogger(imapConfig.email)
        val session = Session.getInstance(props)
        val store =
            if (imapConfig.ssl) session.getStore("imaps") as IMAPStore
            else session.getStore("imap") as IMAPStore

        logger.trace("Connecting to ${imapConfig.email}@${imapConfig.host}")
        store.connect(imapConfig.host, imapConfig.port, imapConfig.username, imapConfig.password)
        logger.trace("Connected to ${imapConfig.email}")

        return store
    }

    private var imapFolderManager: ImapFolderManager? = null

    suspend fun start() {
        imapFolderManager = ImapFolderManager(imapConfig, ::createConnection).apply { start() }
    }

    suspend fun stop() {
        imapFolderManager?.stop()
    }
}