package dev.babies.overmail.util

fun <T> List<T>.dropWhileIndexed(predicate: (index: Int, T) -> Boolean): List<T> {
    var index = 0
    val iterator = this.listIterator(index)
    while (iterator.hasNext() && predicate(index, iterator.next())) {
        index++
    }
    return this.subList(index, this.size)
}