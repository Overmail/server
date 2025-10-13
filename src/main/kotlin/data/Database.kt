package dev.babies.overmail.data

import dev.babies.overmail.data.model.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction

object Database {

    private val host = System.getenv("DB_HOST") ?: "localhost:5432"
    private val database = System.getenv("DB_DATABASE") ?: "overmail"
    private val user = System.getenv("DB_USER") ?: "vocusdev"
    private val password = System.getenv("DB_PASSWORD") ?: "vocus"
    val url = "jdbc:postgresql://$host/$database"

    val db by lazy {
        Database.connect(
            url = url,
            driver = "org.postgresql.Driver",
            user = user,
            password = password
        )
    }

    suspend fun init() {
        query {
            SchemaUtils.create(Users)
            SchemaUtils.create(ImapConfigs, Emails, EmailContent, EmailUsers, EmailSenders, EmailRecipients, ImapFolders)
        }
    }

    suspend fun <T> query(statement: suspend () -> T): T {
        @Suppress("DEPRECATION")
        return newSuspendedTransaction(Dispatchers.IO, db) {
            statement()
        }
    }
}