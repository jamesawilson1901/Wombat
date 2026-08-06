package com.magpie.filer.watch

import java.io.File

/**
 * A file Magpie has seen finish arriving and has not yet dealt with.
 *
 * [size] is what it measured when the file settled; the real file is re-read
 * before anything is copied, so a stale value here can never cause a bad move.
 */
data class SpottedFile(
    val path: String,
    val name: String,
    val size: Long,
    val source: String,
    val spottedAt: Long,
) {
    val file: File get() = File(path)

    /**
     * One notification per file, stable across restarts so a re-offer replaces
     * the old notification instead of stacking a second one.
     */
    val notificationId: Int
        get() {
            val hash = path.hashCode()
            return if (hash == Notifications.ONGOING_ID) hash + 1 else hash
        }
}
