package dev.babies.overmail.api.webapp.realtime

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

    suspend fun getFoldersWatcher(userId: Int): Set<RealtimeSubscription.FoldersSubscription> {
        return editMutex.withLock {
            sessions.getOrDefault(userId, emptySet()).filterIsInstance<RealtimeSubscription.FoldersSubscription>().toSet()
        }
    }

    suspend fun getMailsWatcher(userId: Int, folderId: Int): Set<RealtimeSubscription.MailsSubscription> {
        return editMutex.withLock {
            sessions.getOrDefault(userId, emptySet()).filterIsInstance<RealtimeSubscription.MailsSubscription>()
                .filter { it.folderId == folderId }
                .toSet()
        }
    }

    suspend fun getMailWatcher(userId: Int, emailId: Int): Set<RealtimeSubscription.MailSubscription> {
        return editMutex.withLock {
            sessions.getOrDefault(userId, emptySet()).filterIsInstance<RealtimeSubscription.MailSubscription>()
                .filter { it.emailId == emailId }
                .toSet()
        }
    }

    @Deprecated("Use special methods instead")
    suspend fun getSessions(userId: Int, type: RealtimeSubscriptionType): Set<RealtimeSubscription> {
        return editMutex.withLock {
            sessions.getOrDefault(userId, emptySet()).filter { it.type == type }.toSet()
        }
    }
}

enum class RealtimeSubscriptionType {
    Folders, Mails, Mail
}

sealed class RealtimeSubscription(userId: Int, val type: RealtimeSubscriptionType, session: WebSocketServerSession) {
    data class FoldersSubscription(val userId: Int, val session: WebSocketServerSession): RealtimeSubscription(userId, RealtimeSubscriptionType.Folders, session)
    class MailsSubscription(val userId: Int, val folderId: Int, val session: WebSocketServerSession, var fetched: Long): RealtimeSubscription(userId, RealtimeSubscriptionType.Mails, session)
    data class MailSubscription(val userId: Int, val emailId: Int, val session: WebSocketServerSession): RealtimeSubscription(userId, RealtimeSubscriptionType.Mail, session)
}