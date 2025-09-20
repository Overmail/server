package dev.babies.overmail.api.web.realtime

import io.ktor.server.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object RealtimeManager {
    private val sessions = mutableMapOf<Int, Set<RealtimeSubscription>>()

    val editMutex = Mutex()

    suspend fun addSession(userId: Int, session: RealtimeSubscription) {
        editMutex.withLock {
            sessions[userId] = sessions.getOrDefault(userId, emptySet()) + session
        }
    }

    suspend fun removeSession(userId: Int, session: RealtimeSubscription) {
        editMutex.withLock {
            sessions[userId] = sessions.getOrDefault(userId, emptySet()) - session
        }
    }

    suspend fun getSessions(userId: Int, type: RealtimeSubscriptionType): Set<RealtimeSubscription> {
        return editMutex.withLock {
            sessions.getOrDefault(userId, emptySet()).filter { it.type == type }.toSet()
        }
    }
}

enum class RealtimeSubscriptionType {
    Folders, Mails
}

data class RealtimeSubscription(val userId: Int, val type: RealtimeSubscriptionType, val session: WebSocketServerSession)