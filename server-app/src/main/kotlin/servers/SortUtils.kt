package servers

fun <T : Comparable<T>> List<T>.insertionSorted(): List<T> {
    val result = this.toMutableList()
    for (i in 1 until result.size) {
        for (j in i downTo 1) {
            if (result[j] >= result[j - 1]) break
            result.swap(j, j - 1)
        }
    }

    return result
}

private fun <T> MutableList<T>.swap(i: Int, j: Int) {
    val tmp = this[i]
    this[i] = this[j]
    this[j] = tmp
}