package com.shixiaoshi0417.lunexsu.data.sui

import java.util.concurrent.Future

/** A timed-out shared-shell mutation has an unknown outcome, not a failed outcome. */
internal class SuiLifecycleCommand {
    private var outstanding: Future<*>? = null

    @Synchronized fun isPending(): Boolean = outstanding != null

    @Synchronized fun retain(future: Future<*>) {
        check(outstanding == null) { "A Sui lifecycle command is still unresolved" }
        outstanding = future
    }

    fun <T : Any> reconcile(readStatus: () -> T): T? {
        val expected = synchronized(this) { outstanding } ?: return null
        if (!expected.isDone) return null
        // Even a returned error may follow a partial mutation. Only authoritative status
        // clears uncertainty; never cancel, retry or infer success from the old snapshot.
        runCatching { expected.get() }
        val status = readStatus()
        synchronized(this) {
            check(outstanding === expected)
            outstanding = null
        }
        return status
    }
}
