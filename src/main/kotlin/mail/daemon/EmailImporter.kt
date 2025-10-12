package dev.babies.overmail.mail.daemon

import dev.babies.overmail.api.webapp.realtime.mail.notifyEmailContentUpdated
import dev.babies.overmail.data.Database
import dev.babies.overmail.data.model.Email
import dev.babies.overmail.mail.util.toClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import java.io.File

class EmailImporter {
    private val channel = Channel<Int>(capacity = Channel.UNLIMITED)
    private val workers = mutableMapOf<Int, Job>()
    private val unfinishedEmailIds = mutableSetOf<Int>()

    context(scope: CoroutineScope)
    fun start() {
        repeat(3) { workerId ->
            workers[workerId] = scope.launch {
                for (message in channel) {
                    println("Worker $workerId processing message $message")
                    val email = Database.query { Email[message] }
                    val client = Database.query { email.imapConfig.toClient() }
                    val folderName = Database.query { email.folder.getPath() }
                    client.testConnection()
                    client.use { client ->
                        client.getFolders()
                            .first { it.path == folderName }
                            .let { folder ->
                                val mail = folder.getMails {
                                    envelope = true
                                    uid = true
                                    getUid(email.folderUid)
                                }.first()

                                val tmpDir = File(System.getProperty("java.io.tmpdir"))
                                    .resolve("overmail")
                                    .apply { mkdirs() }

                                val textFile = tmpDir.resolve("${email.folderUid}.txt")
                                val htmlFile = tmpDir.resolve("${email.folderUid}.html")
                                val rawFile = tmpDir.resolve("${email.folderUid}.eml")

                                mail.content.getContent(
                                    rawStream = rawFile.outputStream(),
                                    textStream = textFile.outputStream(),
                                    htmlStream = htmlFile.outputStream()
                                )

                                Database.query {
                                    email.rawSource = ExposedBlob(textFile.inputStream())
                                    email.textBody = textFile.readText()
                                    email.htmlBody = htmlFile.readText().ifBlank { null }
                                    email.state = Email.State.Imported
                                }
                                textFile.delete()
                                htmlFile.delete()
                                rawFile.delete()
                                unfinishedEmailIds.remove(message)
                            }
                        notifyEmailContentUpdated(emailId = email.id.value)
                    }
                    println("Worker $workerId finished message $message")
                }
            }
        }
    }

    fun import(emailId: Int) {
        if (unfinishedEmailIds.contains(emailId)) return
        unfinishedEmailIds.add(emailId)
        channel.trySend(emailId)
    }

    fun stop() {
        channel.close()
        workers.values.forEach { it.cancel() }
    }
}