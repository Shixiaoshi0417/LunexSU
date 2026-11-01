package com.shixiaoshi0417.lunexsu.ui.viewmodel

import com.shixiaoshi0417.lunexsu.data.model.Module
import com.shixiaoshi0417.lunexsu.data.model.ModuleUpdateInfo
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ModuleUpdateChecksTest {
    private val module = Module("id", "name", "author", "v1", 1, "", true, false, false,
        "https://example.org/update.json", false, false, false, null, null)
    private val available = ModuleUpdateInfo("https://example.org/update.zip", "v2", "", 2)

    @Test fun positiveAndNegativeResultsExpireAndManualRefreshBypassesTtl() = runBlocking {
        for (info in listOf(available, ModuleUpdateInfo.Empty)) {
            var time = 0L
            var calls = 0
            val checks = ModuleUpdateChecks({ time }, ttlMillis = 100)
            val fetch: suspend (Module) -> Result<ModuleUpdateInfo> = { calls++; Result.success(info) }
            assertEquals(info, checks.sync(listOf(module), fetch = fetch)[module.id])
            time = 99
            checks.sync(listOf(module), fetch = fetch)
            assertEquals(1, calls)
            time = 100
            checks.sync(listOf(module), fetch = fetch)
            assertEquals(2, calls)
            checks.sync(listOf(module), force = true, fetch = fetch)
            assertEquals(3, calls)
        }
    }

    @Test fun failuresAndOfflineAreRetriedOnTheNextCheck() = runBlocking {
        val checks = ModuleUpdateChecks({ 0L })
        assertTrue(checks.sync(listOf(module)) { Result.failure(IOException("offline")) }.isEmpty())
        assertTrue(checks.sync(listOf(module)) { throw IOException("HTTP 503") }.isEmpty())
        assertEquals(available, checks.sync(listOf(module)) { Result.success(available) }[module.id])
    }

    @Test fun timeoutDoesNotCacheEmptyAndReleasesTheRequest() = runBlocking {
        val checks = ModuleUpdateChecks({ 0L }, timeoutMillis = 10)
        assertTrue(checks.sync(listOf(module)) { CompletableDeferred<Result<ModuleUpdateInfo>>().await() }.isEmpty())
        assertEquals(available, checks.sync(listOf(module)) { Result.success(available) }[module.id])
    }

    @Test fun signatureChangesAndRemovedIdsInvalidateCachedResults() = runBlocking {
        val checks = ModuleUpdateChecks({ 0L })
        checks.sync(listOf(module)) { Result.success(available) }
        var calls = 0
        val changed = module.copy(versionCode = 2)
        assertEquals(ModuleUpdateInfo.Empty, checks.sync(listOf(changed)) {
            calls++; Result.success(ModuleUpdateInfo.Empty)
        }[module.id])
        assertTrue(checks.sync(emptyList()) { error("No modules") }.isEmpty())
        checks.sync(listOf(changed)) { calls++; Result.success(available) }
        assertEquals(2, calls)
    }

    @Test fun cancelledBatchReleasesEveryReservedId() = runBlocking {
        val checks = ModuleUpdateChecks({ 0L })
        val started = CompletableDeferred<Unit>()
        val modules = listOf(module, module.copy(id = "second"))
        val old = async {
            checks.sync(modules) {
                started.complete(Unit)
                CompletableDeferred<Result<ModuleUpdateInfo>>().await()
            }
        }
        started.await()
        old.cancelAndJoin()
        var calls = 0
        val result = checks.sync(modules) { calls++; Result.success(available) }
        assertEquals(2, calls)
        assertEquals(2, result.size)
    }

    @Test fun lateCancelledCleanupCannotRemoveReplacementOwnership() = runBlocking {
        val checks = ModuleUpdateChecks({ 0L })
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val old = async {
            checks.sync(listOf(module)) {
                started.complete(Unit)
                try { CompletableDeferred<Result<ModuleUpdateInfo>>().await() }
                finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }
        }
        started.await()
        old.cancel()
        cleanupStarted.await()
        val newStarted = CompletableDeferred<Unit>()
        val newResult = CompletableDeferred<Result<ModuleUpdateInfo>>()
        val replacement = async {
            checks.sync(listOf(module)) { newStarted.complete(Unit); newResult.await() }
        }
        newStarted.await()
        releaseCleanup.complete(Unit)
        old.join()
        checks.sync(listOf(module)) { error("Replacement ownership was removed by old finally") }
        newResult.complete(Result.success(available))
        assertEquals(available, replacement.await()[module.id])
    }

    @Test fun oldCompletionCannotOverwriteAForcedRefresh() = runBlocking {
        val checks = ModuleUpdateChecks({ 0L })
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = async {
            checks.sync(listOf(module)) {
                started.complete(Unit)
                release.await()
                Result.success(ModuleUpdateInfo.Empty)
            }
        }
        started.await()
        checks.sync(listOf(module), force = true) { Result.success(available) }
        release.complete(Unit)
        assertEquals(available, old.await()[module.id])
        assertEquals(available, checks.sync(listOf(module)) { error("Cache unexpectedly lost") }[module.id])
    }
}
