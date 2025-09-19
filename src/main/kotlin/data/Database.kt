package dev.babies.overmail.data

import dev.babies.overmail.data.model.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

object Database {
    val db by lazy {
        Database.connect(
            url = "jdbc:postgresql://localhost:5432/overmail",
            driver = "org.postgresql.Driver",
            user = "vocusdev",
            password = "vocus"
        )
    }
    fun init() {
        query {
            SchemaUtils.create(Users)
            SchemaUtils.create(ImapConfigs, Emails, EmailUsers, EmailSenders, EmailRecipients, ImapFolders)
        }
    }

    fun <T> query(statement: () -> T): T {
        return transaction(db) {
            statement()
        }
    }
}