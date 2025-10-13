package dev.babies.overmail.mail.daemon

import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.ImapConfig
import dev.babies.overmail.data.model.ImapConfigs
import io.ktor.server.application.*
import io.ktor.util.*
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

    private val imapDaemons = mutableMapOf<ImapConfigId, ImapDaemon>()
    private lateinit var coroutineScope: CoroutineScope

    fun getDaemonForImapConfig(imapConfigId: Int): ImapDaemon? {
        return imapDaemons[imapConfigId]
    }

    override fun install(pipeline: Application, configure: Unit.() -> Unit) {
        coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        pipeline.monitor.subscribe(ApplicationStarted) {
            coroutineScope.launch {
                val imapConfigs = Database.query { ImapConfigs.select(ImapConfigs.id).map { it[ImapConfigs.id].value } }
                imapConfigs.forEach { imapConfigId ->
                    coroutineScope.launch {
                        initImapConfig(imapConfigId)
                    }
                }
            }
        }

        pipeline.monitor.subscribe(ApplicationStopped) {
        }
    }

    private val imapDaemonChangeMutex = Mutex()
    suspend fun initImapConfig(imapConfigId: Int) {
        imapDaemonChangeMutex.withLock {
            if (imapConfigId in imapDaemons) {
                val daemon = imapDaemons[imapConfigId]!!
                daemon.stop()
                imapDaemons.remove(imapConfigId)
            }

            val imapConfig = Database.query { ImapConfig[imapConfigId] }
            imapDaemons[imapConfigId] = ImapDaemon(imapConfig, coroutineScope)
        }
    }
}