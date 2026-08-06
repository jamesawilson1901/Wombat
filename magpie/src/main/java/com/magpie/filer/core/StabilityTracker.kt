package com.magpie.filer.core

/**
 * Decides when a file has finished arriving.
 *
 * There is no API that says "this download is complete", so the test is
 * behavioural: a non-zero size that has not changed between two consecutive
 * looks a couple of seconds apart. A file still being written grows, or sits at
 * zero, and fails the test until it stops.
 *
 * Not thread-safe: the watcher calls it from one coroutine.
 */
class StabilityTracker(private val settleMillis: Long = 2_200L) {

    private data class Look(val size: Long, val at: Long)

    private val looks = HashMap<String, Look>()

    /**
     * Record a sighting of [path] at [size]. Returns true once the size has
     * held steady for long enough, and keeps returning true until the caller
     * calls [forget] — the caller decides when it has acted on the file.
     */
    fun observe(path: String, size: Long, now: Long): Boolean {
        val previous = looks[path]
        if (previous == null || previous.size != size) {
            looks[path] = Look(size, now)
            return false
        }
        if (size <= 0L) return false
        return now - previous.at >= settleMillis
    }

    fun forget(path: String) {
        looks.remove(path)
    }

    /** Drop anything that has since vanished from the watched folders. */
    fun retainOnly(paths: Set<String>) {
        looks.keys.retainAll(paths)
    }

    /** How many files are mid-flight. The watcher polls faster when any are. */
    val pending: Int get() = looks.size
}
