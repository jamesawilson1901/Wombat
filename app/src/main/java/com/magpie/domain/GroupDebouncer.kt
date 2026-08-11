package com.magpie.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * §5 grouping: each completed file (re)starts a debounce timer; everything
 * arriving inside the window joins one group, capped at [maxWaitMs] total so
 * a long download queue still gets a popup.
 */
class GroupDebouncer(
    private val scope: CoroutineScope,
    private val debounceMs: () -> Long,
    private val maxWaitMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onGroup: suspend (List<String>) -> Unit,
) {
    private val mutex = Mutex()
    private val pending = LinkedHashSet<String>()
    private var firstAddedAt = 0L
    private var timer: Job? = null

    fun add(path: String) {
        scope.launch {
            mutex.withLock {
                if (pending.isEmpty()) firstAddedAt = clock()
                pending.add(path)
                timer?.cancel()
                val elapsed = clock() - firstAddedAt
                val wait = minOf(debounceMs(), (maxWaitMs - elapsed).coerceAtLeast(0L))
                timer = scope.launch {
                    delay(wait)
                    fire()
                }
            }
        }
    }

    private suspend fun fire() {
        val group: List<String> = mutex.withLock {
            val g = pending.toList()
            pending.clear()
            timer = null
            g
        }
        if (group.isNotEmpty()) onGroup(group)
    }
}
