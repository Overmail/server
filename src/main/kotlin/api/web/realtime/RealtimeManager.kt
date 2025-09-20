package dev.babies.overmail.api.web.realtime

import io.ktor.server.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object RealtimeManager {
    private val sessions = mutableMapOf<Int, Set<WebSocketServerSession>>()

    val editMutex = Mutex()

    suspend fun addSession(userId: Int, session: WebSocketServerSession) {
        editMutex.withLock {
            sessions[userId] = sessions.getOrDefault(userId, emptySet()) + session
        }
    }

    suspend fun removeSession(userId: Int, session: WebSocketServerSession) {
        editMutex.withLock {
            sessions[userId] = sessions.getOrDefault(userId, emptySet()) - session
        }
    }

    suspend fun getSessions(userId: Int): Set<WebSocketServerSession> {
        return editMutex.withLock {
            sessions.getOrDefault(userId, emptySet())
        }
    }
}