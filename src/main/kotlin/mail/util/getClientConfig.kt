package dev.babies.overmail.mail.util

import com.overmail.core.ImapClient
import dev.babies.overmail.data.model.ImapConfig

fun ImapConfig.toClient(): ImapClient {
    return ImapClient(
        host = this.host,
        port = this.port,
        ssl = this.ssl,
        username = this.username,
        password = this.password,
    )
}