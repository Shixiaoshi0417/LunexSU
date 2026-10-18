package com.shixiaoshi0417.lunexsu.data.themestore

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.shixiaoshi0417.lunexsu.ui.viewmodel.ThemeStoreUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real downloader/OkHttp body reads, deliberately blocked after a known progress update. */
class ThemeDownloaderRaceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val remote = RemoteTheme("399", "Theme", "Author", "", "1", "", "https://example.org/theme.fpt", "phone", "official")

    private class BlockedBody(val prefix: Int = 16, val fail: Boolean = false) : ResponseBody() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        private var reads = 0
        private val stream = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = when (reads++) {
                0 -> { sink.write(ByteArray(prefix) { 1 }); prefix.toLong() }
                1 -> {
                    blocked.countDown()
                    if (!release.await(10, TimeUnit.SECONDS)) throw IOException("Test body was not released")
                    if (fail) throw IOException("Old blocked read failed")
                    sink.write(ByteArray(32 - prefix) { 2 })
                    (32 - prefix).toLong()
                }
                else -> -1L
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        override fun contentType() = null
        override fun contentLength() = 32L
        override fun source(): BufferedSource = stream
        fun awaitBlocked() = assertTrue("Download never reached blocked read", blocked.await(10, TimeUnit.SECONDS))
    }

    private class Harness(root: File, vararg bodies: ResponseBody) {
        private val queue = LinkedBlockingQueue(bodies.toList())
        val requests = AtomicInteger()
        val ranges = mutableListOf<String?>()
        val downloader = ThemeDownloader(root, OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            synchronized(ranges) { ranges += chain.request().header("Range") }
            val body = queue.poll() ?: throw AssertionError("Unexpected retry or extra worker")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (chain.request().header("Range") != null) 206 else 200).message("OK").body(body).build()
        }.build())
    }

    private fun CoroutineScope.collect(harness: Harness, theme: RemoteTheme = remote): Pair<Job, MutableList<DownloadProgress>> {
        val events = mutableListOf<DownloadProgress>()
        return launch(Dispatchers.Default) { harness.downloader.downloadTheme(theme).toList(events) } to events
    }

    @Test fun cancelledOldReadCannotOverwriteOrRemoveReplacementProgress() = oldReadAfterRestart(fail = false)
    @Test fun cancelledOldFailureCannotRetryOrRemoveReplacementProgress() = oldReadAfterRestart(fail = true)

    private fun oldReadAfterRestart(fail: Boolean) = runBlocking {
        val old = BlockedBody(fail = fail)
        val replacement = BlockedBody(prefix = 8)
        val harness = Harness(temporary.root, old, replacement)
        val (oldJob, oldEvents) = collect(harness)
        try {
            old.awaitBlocked()
            harness.downloader.cancelDownload(remote.id)
            val (newJob, _) = collect(harness, remote.copy(version = "2"))
            replacement.awaitBlocked()
            val progress = harness.downloader.downloadProgress.value[remote.id]
            assertEquals(0.25f, progress!!.fileProgress, 0f)
            old.release.countDown()
            withTimeout(10_000) { oldJob.join() }
            assertEquals(progress, harness.downloader.downloadProgress.value[remote.id])
            assertTrue(harness.downloader.scanLocalThemes().isEmpty())
            assertFalse(oldEvents.any { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.COMPLETED })
            assertEquals(2, harness.requests.get())
            // The old worker only cleans its own staging; the replacement still owns its bytes.
            assertEquals(1, File(temporary.root, "theme_downloads").listFiles()!!.size)
            replacement.release.countDown()
            withTimeout(10_000) { newJob.join() }
            assertEquals(DownloadStatus.COMPLETED, harness.downloader.downloadProgress.value[remote.id]!!.status)
            assertEquals("2", harness.downloader.scanLocalThemes().single().version)
            assertTrue(File(temporary.root, "theme_downloads").listFiles()!!.isEmpty())
        } finally {
            old.release.countDown()
            replacement.release.countDown()
        }
    }

    @Test fun oldCompletionAfterReplacementPublicationCannotRemoveCompletedRevision() = runBlocking {
        val old = BlockedBody()
        val replacement = BlockedBody(prefix = 8)
        val harness = Harness(temporary.root, old, replacement)
        val (oldJob, _) = collect(harness)
        try {
            old.awaitBlocked()
            harness.downloader.cancelDownload(remote.id)
            val (newJob, _) = collect(harness, remote.copy(version = "2"))
            replacement.awaitBlocked()
            replacement.release.countDown()
            withTimeout(10_000) { newJob.join() }
            val published = harness.downloader.scanLocalThemes().single()
            old.release.countDown()
            withTimeout(10_000) { oldJob.join() }
            assertEquals(published, harness.downloader.scanLocalThemes().single())
            assertEquals(32L, File(published.localPath).length())
            assertEquals(DownloadStatus.COMPLETED, harness.downloader.downloadProgress.value[remote.id]!!.status)
        } finally {
            old.release.countDown()
            replacement.release.countDown()
        }
    }

    @Test fun repeatedRestartLeavesReplacementCancellableAndNoStagingOrPublication() = runBlocking {
        val bodies = Array(5) { BlockedBody(prefix = 8, fail = it % 2 == 0) }
        val harness = Harness(temporary.root, *bodies)
        var (job, _) = collect(harness)
        try {
            bodies[0].awaitBlocked()
            for (index in 1 until bodies.size) {
                harness.downloader.cancelDownload(remote.id)
                val (replacement, _) = collect(harness)
                bodies[index].awaitBlocked()
                bodies[index - 1].release.countDown()
                withTimeout(10_000) { job.join() }
                assertEquals(DownloadStatus.DOWNLOADING, harness.downloader.downloadProgress.value[remote.id]!!.status)
                job = replacement
            }
            harness.downloader.cancelDownload(remote.id)
            bodies.last().release.countDown()
            withTimeout(10_000) { job.join() }
            assertTrue(harness.downloader.downloadProgress.value.isEmpty())
            assertTrue(harness.downloader.scanLocalThemes().isEmpty())
            assertTrue(File(temporary.root, "theme_downloads").listFiles()!!.isEmpty())
            assertEquals(bodies.size, harness.requests.get())
        } finally {
            bodies.forEach { it.release.countDown() }
        }
    }

    @Test fun cancellingCollectorWhileReadIsBlockedAlsoCleansOnlyItsOwnStage() = runBlocking {
        val old = BlockedBody()
        val replacement = BlockedBody(prefix = 8)
        val harness = Harness(temporary.root, old, replacement)
        val (oldJob, _) = collect(harness)
        try {
            old.awaitBlocked()
            oldJob.cancel()
            harness.downloader.cancelDownload(remote.id)
            val (newJob, _) = collect(harness)
            replacement.awaitBlocked()
            val progress = harness.downloader.downloadProgress.value[remote.id]
            old.release.countDown()
            withTimeout(10_000) { oldJob.join() }
            assertEquals(progress, harness.downloader.downloadProgress.value[remote.id])
            harness.downloader.cancelDownload(remote.id)
            replacement.release.countDown()
            withTimeout(10_000) { newJob.join() }
            assertTrue(harness.downloader.scanLocalThemes().isEmpty())
            assertTrue(harness.downloader.downloadProgress.value.isEmpty())
        } finally {
            old.release.countDown()
            replacement.release.countDown()
        }
    }

    @Test fun pauseDoesNotAllowAnotherWriterUntilOriginalCollectorStops() = runBlocking {
        val body = BlockedBody()
        val harness = Harness(temporary.root, body)
        val (job, _) = collect(harness)
        try {
            body.awaitBlocked()
            harness.downloader.pauseDownload(remote.id)
            val (duplicateJob, duplicateEvents) = collect(harness)
            withTimeout(10_000) { duplicateJob.join() }
            assertEquals(DownloadStatus.PENDING, duplicateEvents.single().status)
            assertEquals(1, harness.requests.get())
            body.release.countDown()
            withTimeout(10_000) { job.join() }
            assertEquals(DownloadStatus.PAUSED, harness.downloader.downloadProgress.value[remote.id]!!.status)
            val stage = File(temporary.root, "theme_downloads").listFiles()!!.single()
            assertEquals(16L, stage.listFiles()!!.single().length())
            harness.downloader.cancelDownload(remote.id)
            assertFalse(stage.exists())
        } finally {
            body.release.countDown()
        }
    }

    @Test fun pausedTaskResumesItsOwnPartialFileAndPublishes() = runBlocking {
        val body = BlockedBody()
        val harness = Harness(temporary.root, body, ByteArray(16) { 2 }.toResponseBody())
        val (job, _) = collect(harness)
        try {
            body.awaitBlocked()
            harness.downloader.pauseDownload(remote.id)
            body.release.countDown()
            withTimeout(10_000) { job.join() }
            assertEquals(DownloadStatus.PAUSED, harness.downloader.downloadProgress.value[remote.id]!!.status)
            val (resumed, _) = collect(harness)
            withTimeout(10_000) { resumed.join() }
            assertEquals(listOf(null, "bytes=16-"), harness.ranges)
            assertEquals(DownloadStatus.COMPLETED, harness.downloader.downloadProgress.value[remote.id]!!.status)
            assertEquals(32L, File(harness.downloader.scanLocalThemes().single().localPath).length())
        } finally {
            body.release.countDown()
        }
    }

    @Test fun completedThemeCanBeAppliedBeforeLocalScan() = applyBeforeScan(replace = false)

    @Test fun replacementCanBeAppliedBeforeStaleLocalListRefresh() = applyBeforeScan(replace = true)

    private fun applyBeforeScan(replace: Boolean) = runBlocking {
        val bodies = Array(if (replace) 2 else 1) { ByteArray(32) { 1 }.toResponseBody() }
        val harness = Harness(temporary.root, *bodies)
        val old = if (replace) {
            harness.downloader.downloadTheme(remote).toList()
            harness.downloader.scanLocalThemes().single()
        } else null
        val staleState = ThemeStoreUiState(localThemes = listOfNotNull(old))
        val target = if (replace) remote.copy(version = "2") else remote
        val completed = CompletableDeferred<Unit>()
        val allowRefresh = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            harness.downloader.downloadTheme(target).collect { progress ->
                if (progress.status == DownloadStatus.COMPLETED) {
                    completed.complete(Unit)
                    // Model the ViewModel collector suspended before its local library scan.
                    allowRefresh.await()
                }
            }
        }
        try {
            withTimeout(10_000) { completed.await() }
            val progress = harness.downloader.downloadProgress.value[target.id]
            assertEquals(DownloadStatus.COMPLETED, progress?.status)
            val applicable = staleState.localTheme(target.id, progress)
            assertNotNull(applicable)
            assertTrue(applicable!!.matchesRevision(target))
            assertEquals(32L, File(applicable.localPath).length())
            if (old != null) {
                assertNotEquals(old.localPath, applicable.localPath)
                assertFalse(File(old.localPath).exists())
            }
        } finally {
            allowRefresh.complete(Unit)
            withTimeout(10_000) { job.join() }
        }
    }

    @Test fun deletingCompletedThemeClearsImmediateApplyFallback() = runBlocking {
        val harness = Harness(temporary.root, ByteArray(32) { 1 }.toResponseBody())
        harness.downloader.downloadTheme(remote).toList()
        val state = ThemeStoreUiState()
        val local = state.localTheme(remote.id, harness.downloader.downloadProgress.value[remote.id])!!
        harness.downloader.deleteLocalTheme(local)
        assertNull(state.localTheme(remote.id, harness.downloader.downloadProgress.value[remote.id]))
        assertFalse(File(local.localPath).exists())
    }

    @Test fun onlyMatchingCompletedRevisionSuppliesImmediateApplyFallback() = runBlocking {
        val harness = Harness(temporary.root, ByteArray(32) { 1 }.toResponseBody())
        harness.downloader.downloadTheme(remote).toList()
        val state = ThemeStoreUiState()
        val progress = harness.downloader.downloadProgress.value[remote.id]!!
        assertNull(state.localTheme(remote.id, progress.copy(status = DownloadStatus.DOWNLOADING)))
        assertNull(state.localTheme(remote.id, progress.copy(themeId = "other")))
        assertNull(state.localTheme(remote.id, progress.copy(completedTheme = progress.completedTheme!!.copy(id = "other"))))
        assertNull(state.localTheme(remote.id, progress.copy(completedTheme = null)))
    }

}
