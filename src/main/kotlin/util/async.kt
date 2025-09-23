package dev.babies.overmail.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

@Suppress("unused")
context(scope: CoroutineScope)
suspend fun <T, R> Iterable<T>.mapAsync(block: suspend (T) -> R): Iterable<R> = this
    .map { scope.async { block(it) } }
    .awaitAll()

context(scope: CoroutineScope)
suspend fun <T> Iterable<T>.forEachAsync(block: suspend (T) -> Unit) = this
    .map { scope.async { block(it) } }
    .awaitAll()