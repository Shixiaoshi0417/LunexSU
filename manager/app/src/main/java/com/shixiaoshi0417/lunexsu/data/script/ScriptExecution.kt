package com.shixiaoshi0417.lunexsu.data.script

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Every started run releases its extraction and leaves RUNNING, even if shell setup fails. */
internal suspend fun executeScriptLifecycle(
    execute: () -> Int,
    record: (Int) -> Unit,
    cleanup: () -> Unit,
    release: () -> Unit,
    finished: (Int) -> Unit,
    onFailure: (Exception) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    var exitCode = -1
    fun report(error: Exception) {
        // Reporting must not prevent the remaining cleanup or unlock the page too late.
        runCatching { onFailure(error) }
    }
    try {
        exitCode = withContext(dispatcher) { execute() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report(e)
    } finally {
        try {
            withContext(NonCancellable + dispatcher) {
                for (action in listOf({ record(exitCode) }, cleanup, release)) {
                    try {
                        action()
                    } catch (e: Exception) {
                        report(e)
                    }
                }
            }
        } finally {
            finished(exitCode)
        }
    }
}
