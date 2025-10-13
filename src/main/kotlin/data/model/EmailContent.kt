package dev.babies.overmail.data.model

import org.jetbrains.exposed.v1.core.dao.id.IdTable

object EmailContent : IdTable<Int>("email_content") {
    override val id = reference("email", Emails)
    val textContent = blob("text_content").nullable()
    val textSize = long("text_size").nullable()
    val htmlContent = blob("html_content").nullable()
    val htmlSize = long("html_size").nullable()
    val rawContent = blob("raw_content").nullable()
    val rawSize = long("raw_size").nullable()
}