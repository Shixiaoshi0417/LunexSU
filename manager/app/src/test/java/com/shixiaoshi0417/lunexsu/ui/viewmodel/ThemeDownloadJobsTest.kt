package com.shixiaoshi0417.lunexsu.ui.viewmodel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeDownloadJobsTest {
    @Test fun lateCancelledCompletionCannotRemoveReplacement() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        var active = emptySet<String>()
        val jobs = ThemeDownloadJobs(scope) { active = it }
        val oldCleanup = CompletableDeferred<Unit>()
        val releaseOldCleanup = CompletableDeferred<Unit>()
        val replacementFinished = CompletableDeferred<Unit>()
        try {
            jobs.start("theme") {
                try {
                    CompletableDeferred<Unit>().await()
                } finally {
                    withContext(NonCancellable) {
                        oldCleanup.complete(Unit)
                        releaseOldCleanup.await()
                    }
                }
            }
            var downloaderCancelled = false
            jobs.cancel("theme") { downloaderCancelled = true }
            oldCleanup.await()
            assertTrue(downloaderCancelled)
            assertTrue(active.isEmpty())

            var replacementCancelled = false
            jobs.start("theme") {
                try {
                    replacementFinished.await()
                } finally {
                    replacementCancelled = true
                }
            }
            releaseOldCleanup.complete(Unit)
            assertEquals(setOf("theme"), active)

            var duplicateStarted = false
            jobs.start("theme") { duplicateStarted = true }
            assertFalse(duplicateStarted)
            jobs.cancel("theme") {}
            assertTrue(active.isEmpty())
            assertTrue(replacementCancelled)
        } finally {
            releaseOldCleanup.complete(Unit)
            scope.cancel()
        }
    }

    @Test fun repeatedRestartsKeepOnlyCurrentJobAndItsUpdates() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        var active = emptySet<String>()
        val jobs = ThemeDownloadJobs(scope) { active = it }
        val cleanups = mutableListOf<CompletableDeferred<Unit>>()
        var published = 0
        var finished = 0
        try {
            repeat(3) { generation ->
                val releaseCleanup = CompletableDeferred<Unit>()
                cleanups += releaseCleanup
                jobs.start("theme") {
                    try {
                        jobs.updateIfCurrent("theme") { published = generation + 1 }
                        CompletableDeferred<Unit>().await()
                    } finally {
                        withContext(NonCancellable) {
                            releaseCleanup.await()
                            jobs.updateIfCurrent("theme") { published = -1 }
                        }
                        finished++
                    }
                }
                assertEquals(generation + 1, published)
                jobs.cancel("theme") {}
                assertTrue(active.isEmpty())
            }
            val replacement = CompletableDeferred<Unit>()
            var replacementCancelled = false
            jobs.start("theme") {
                try {
                    replacement.await()
                } finally {
                    replacementCancelled = true
                }
            }
            cleanups.forEach { it.complete(Unit) }
            assertEquals(3, finished)
            assertEquals(3, published)
            assertEquals(setOf("theme"), active)
            jobs.cancel("theme") {}
            assertTrue(replacementCancelled)
            assertTrue(active.isEmpty())
        } finally {
            cleanups.forEach { it.complete(Unit) }
            scope.cancel()
        }
    }

    @Test fun restartWaitsForDownloaderCancellation() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val jobs = ThemeDownloadJobs(scope) {}
        val executor = Executors.newFixedThreadPool(2)
        val cancellationEntered = CountDownLatch(1)
        val releaseCancellation = CountDownLatch(1)
        val restartAttempted = CountDownLatch(1)
        var cancellationFinished = false
        var replacementSawCancellation = false
        try {
            jobs.start("theme") { CompletableDeferred<Unit>().await() }
            val cancellation = executor.submit {
                jobs.cancel("theme") {
                    cancellationEntered.countDown()
                    check(releaseCancellation.await(5, TimeUnit.SECONDS))
                    cancellationFinished = true
                }
            }
            assertTrue(cancellationEntered.await(5, TimeUnit.SECONDS))
            val restart = executor.submit {
                restartAttempted.countDown()
                jobs.start("theme") {
                    replacementSawCancellation = cancellationFinished
                    CompletableDeferred<Unit>().await()
                }
            }
            assertTrue(restartAttempted.await(5, TimeUnit.SECONDS))
            releaseCancellation.countDown()
            cancellation.get(5, TimeUnit.SECONDS)
            restart.get(5, TimeUnit.SECONDS)
            assertTrue(replacementSawCancellation)
        } finally {
            releaseCancellation.countDown()
            scope.cancel()
            executor.shutdownNow()
        }
    }

    @Test fun immediateCompletionDoesNotLeaveRegisteredJob() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        var active = emptySet<String>()
        val jobs = ThemeDownloadJobs(scope) { active = it }
        try {
            var starts = 0
            repeat(2) {
                jobs.start("theme") { starts++ }
                assertTrue(active.isEmpty())
            }
            assertEquals(2, starts)
        } finally {
            scope.cancel()
        }
    }

    @Test fun alreadyCancelledScopeDoesNotLeaveRegisteredJob() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        scope.cancel()
        var active = emptySet<String>()
        val jobs = ThemeDownloadJobs(scope) { active = it }
        var started = false
        jobs.start("theme") { started = true }
        assertFalse(started)
        assertTrue(active.isEmpty())
    }

    @Test fun finishingOneThemeKeepsOtherThemeActive() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        var active = emptySet<String>()
        val jobs = ThemeDownloadJobs(scope) { active = it }
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        try {
            jobs.start("first") { first.await() }
            jobs.start("second") { second.await() }
            assertEquals(setOf("first", "second"), active)
            first.complete(Unit)
            assertEquals(setOf("second"), active)
            second.complete(Unit)
            assertTrue(active.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
