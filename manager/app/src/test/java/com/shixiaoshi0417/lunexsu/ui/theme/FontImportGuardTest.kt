package com.shixiaoshi0417.lunexsu.ui.theme

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontImportGuardTest {
    @Test
    fun slowImportCannotOverrideRestoreOrSystemSelection() {
        for (selection in listOf("restored", "system")) {
            val guard = FontImportGuard()
            val started = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val value = AtomicReference("old custom font")
            val applied = AtomicReference<Boolean>()
            val worker = Thread {
                val token = guard.begin()
                started.countDown()
                if (finish.await(5, TimeUnit.SECONDS)) {
                    applied.set(guard.commit(token) { value.set("imported") })
                }
            }
            worker.start()
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                guard.change { value.set(selection) }
            } finally {
                finish.countDown()
                worker.join(5000)
            }
            assertFalse(worker.isAlive)
            assertEquals(false, applied.get())
            assertEquals(selection, value.get())
        }
    }

    @Test
    fun lastImportWinsEvenWhenOlderImportCompletesLater() {
        val guard = FontImportGuard()
        var font = "system"
        val old = guard.begin()
        val latest = guard.begin()
        assertTrue(guard.commit(latest) { font = "latest" })
        assertFalse(guard.commit(old) { font = "old" })
        assertEquals("latest", font)
    }

    @Test
    fun selectionAfterCompletedImportIsPreserved() {
        val guard = FontImportGuard()
        var font = "system"
        assertTrue(guard.commit(guard.begin()) { font = "custom" })
        guard.change { font = "system" }
        assertEquals("system", font)
    }
}
