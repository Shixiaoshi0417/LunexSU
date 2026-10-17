package com.shixiaoshi0417.lunexsu.data

import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

/** Invalidated resources stay alive until their last user finishes, without locking around work. */
internal class SessionResourceCache<T : AutoCloseable>(
    private val create: () -> T,
    private val onCloseFailure: (Exception) -> Unit = {},
) {
    private class Entry<T>(val value: T, var users: Int = 0, var retired: Boolean = false)
    private val lock = Any()
    private var current: Entry<T>? = null

    private fun dispose(value: T) {
        try { value.close() } catch (e: Exception) { onCloseFailure(e) }
    }

    private fun acquire(): Pair<T, () -> Unit> {
        val entry = synchronized(lock) {
            (current ?: Entry(create()).also { current = it }).also { it.users++ }
        }
        val released = AtomicBoolean()
        return entry.value to {
            if (released.compareAndSet(false, true)) {
                val close = synchronized(lock) { --entry.users == 0 && entry.retired }
                if (close) dispose(entry.value)
            }
        }
    }

    /** The value must not escape the block, including through jobs or streams still in use. */
    fun <R> use(block: (T) -> R): R {
        val (value, release) = acquire()
        return try { block(value) } finally { release() }
    }

    /** Cancel(false) does not retire a resource while the running operation is still using it. */
    fun <R> submit(executor: Executor, block: (T) -> R): Future<R> =
        FutureTask { use(block) }.also { executor.execute(it) }

    fun invalidate() {
        val unused = synchronized(lock) {
            val previous = current
            current = null
            previous?.retired = true
            previous?.takeIf { it.users == 0 }
        }
        if (unused != null) dispose(unused.value)
    }
}
