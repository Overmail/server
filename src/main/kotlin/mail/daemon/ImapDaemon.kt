package dev.babies.overmail.mail.daemon

import dev.babies.overmail.data.model.ImapConfig
import jakarta.mail.Session
import kotlinx.coroutines.CoroutineScope
import java.util.Properties

class ImapDaemon(
    imapConfig: ImapConfig,
    coroutineScope: CoroutineScope
) {
    private val properties = Properties()
    val session: Session
    val folderDaemon: FolderDaemon

    init {
        properties["mail.store.protocol"] = "imap"
        properties["mail.imap.host"] = imapConfig.host
        properties["mail.imap.port"] = imapConfig.port
        properties["mail.imap.ssl.enable"] = imapConfig.ssl
        properties["mail.imap.connectionpoolsize"] = 500
        properties["mail.imap.connectionpooltimeout"] = 60000
        properties["mail.imap.timeout"] = 60000
        properties["mail.imap.idle.timeout"] = 60000
        properties["mail.imap.partialfetch"] = false

        session = Session.getInstance(properties)

        folderDaemon = FolderDaemon(imapConfig, coroutineScope, this)
    }

    fun stop() {
        folderDaemon.stop()
    }
}