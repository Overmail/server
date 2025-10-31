package dev.babies.overmail.mail.daemon

import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.ImapConfig
import dev.babies.overmail.data.model.ImapConfigs
import io.ktor.server.application.*
import io.ktor.util.*
import io.ktor.util.logging.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.jdbc.select

typealias ImapConfigId = Int
object DaemonManagerPlugin : BaseApplicationPlugin<Application, Unit, Unit> {
    override val key: AttributeKey<Unit> = io.ktor.util.AttributeKey("DaemonManagerPlugin")

    private val logger = KtorSimpleLogger("DaemonManagerPlugin")
    private val imapDaemons = mutableMapOf<ImapConfigId, ImapDaemon>()
    private lateinit var coroutineScope: CoroutineScope

    fun getDaemonForImapConfig(imapConfigId: Int): ImapDaemon? {
        return imapDaemons[imapConfigId]
    }

    override fun install(pipeline: Application, configure: Unit.() -> Unit) {
        coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        pipeline.monitor.subscribe(ApplicationStarted) {
            coroutineScope.launch {
                try {
                    val imapConfigs = Database.query { ImapConfigs.select(ImapConfigs.id).map { it[ImapConfigs.id].value } }
                    logger.info("Starting ${imapConfigs.size} IMAP daemon(s)")
                    imapConfigs.forEach { imapConfigId ->
                        coroutineScope.launch {
                            try {
                                initImapConfig(imapConfigId)
                            } catch (e: Exception) {
                                logger.error("Failed to initialize IMAP config $imapConfigId: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to load IMAP configs: ${e.message}")
                }
            }
        }

        pipeline.monitor.subscribe(ApplicationStopped) {
            logger.info("Stopping all IMAP daemons")
            imapDaemons.values.forEach { daemon ->
                try {
                    daemon.stop()
                } catch (e: Exception) {
                    logger.error("Error stopping daemon: ${e.message}")
                }
            }
            imapDaemons.clear()
        }
    }

    private val imapDaemonChangeMutex = Mutex()
    suspend fun initImapConfig(imapConfigId: Int) {
        imapDaemonChangeMutex.withLock {
            if (imapConfigId in imapDaemons) {
                logger.info("Stopping existing daemon for IMAP config $imapConfigId")
                val daemon = imapDaemons[imapConfigId]!!
                try {
                    daemon.stop()
                } catch (e: Exception) {
                    logger.error("Error stopping daemon: ${e.message}")
                }
                imapDaemons.remove(imapConfigId)
            }

            try {
                val imapConfig = Database.query { ImapConfig[imapConfigId] }
                logger.info("Starting daemon for IMAP config $imapConfigId (${imapConfig.email})")
                imapDaemons[imapConfigId] = ImapDaemon(imapConfig, coroutineScope)
            } catch (e: Exception) {
                logger.error("Failed to initialize IMAP config $imapConfigId: ${e.message}")
                throw e
            }
        }
    }
}