package com.shixiaoshi0417.lunexsu.data

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SessionResourceCacheTest {
    private class Resource(val generation: Int) : AutoCloseable {
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet() }
    }

    @Test fun lazyCreationAndIdleInvalidationCloseEachGenerationOnce() {
        val created = AtomicInteger()
        val cache = SessionResourceCache(create = { Resource(created.incrementAndGet()) })
        assertEquals(0, created.get())
        val old = cache.use { it }
        cache.use { assertSame(old, it) }
        cache.invalidate()
        cache.invalidate()
        assertEquals(1, old.closes.get())
        cache.use { assertEquals(2, it.generation); assertEquals(0, it.closes.get()) }
        cache.invalidate()
    }

    @Test fun oldUserFinishesWhileNewUsersGetTheNewGeneration() {
        val created = AtomicInteger()
        val cache = SessionResourceCache(create = { Resource(created.incrementAndGet()) })
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        try {
            val running = cache.submit(executor) {
                entered.countDown()
                check(proceed.await(10, TimeUnit.SECONDS))
                assertEquals(0, it.closes.get())
                it
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            cache.invalidate()
            cache.use { assertEquals(2, it.generation); assertEquals(0, it.closes.get()) }
            proceed.countDown()
            val old = running.get(10, TimeUnit.SECONDS)
            assertEquals(1, old.generation)
            assertEquals(1, old.closes.get())
        } finally { proceed.countDown(); executor.shutdownNow(); cache.invalidate() }
    }

    @Test fun nestedUsersPinAnInvalidatedGenerationUntilTheLastRelease() {
        val cache = SessionResourceCache(create = { Resource(1) })
        lateinit var old: Resource
        cache.use { first ->
            old = first
            cache.use { second ->
                assertSame(first, second)
                cache.invalidate()
                assertEquals(0, first.closes.get())
            }
            assertEquals(0, first.closes.get())
        }
        assertEquals(1, old.closes.get())
    }

    @Test fun throwingUserStillReleasesAnInvalidatedGeneration() {
        val cache = SessionResourceCache(create = { Resource(1) })
        val old = cache.use { it }
        assertThrows(IllegalStateException::class.java) {
            cache.use { cache.invalidate(); error("Operation failed") }
        }
        assertEquals(1, old.closes.get())
    }

    @Test fun cancellingRunningFutureDoesNotCloseItsResourcePrematurely() {
        val cache = SessionResourceCache(create = { Resource(1) })
        val old = cache.use { it }
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        try {
            val future = cache.submit(executor) {
                entered.countDown()
                check(proceed.await(10, TimeUnit.SECONDS))
                assertEquals(0, it.closes.get())
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(future.cancel(false))
            cache.invalidate()
            assertEquals(0, old.closes.get())
            proceed.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            assertEquals(1, old.closes.get())
        } finally { proceed.countDown(); executor.shutdownNow(); cache.invalidate() }
    }

    @Test fun cancellingQueuedFutureDoesNotAcquireAResource() {
        val created = AtomicInteger()
        val cache = SessionResourceCache(create = { Resource(created.incrementAndGet()) })
        val executor = Executors.newSingleThreadExecutor()
        val proceed = CountDownLatch(1)
        try {
            executor.execute { check(proceed.await(10, TimeUnit.SECONDS)) }
            val future = cache.submit(executor) { fail("Cancelled operation ran") }
            assertTrue(future.cancel(false))
            proceed.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            assertEquals(0, created.get())
        } finally { proceed.countDown(); executor.shutdownNow() }
    }

    @Test fun failedCreationIsNotPublishedAndCanBeRetried() {
        val attempts = AtomicInteger()
        val cache = SessionResourceCache(create = {
            check(attempts.incrementAndGet() > 1)
            Resource(2)
        })
        assertThrows(IllegalStateException::class.java) { cache.use { fail("Failed resource published") } }
        cache.use { assertEquals(2, it.generation) }
        cache.invalidate()
    }

    @Test fun closeFailureDoesNotPoisonTheNextGeneration() {
        val failures = AtomicInteger()
        val cache = SessionResourceCache(create = { AutoCloseable { error("Close failed") } },
            onCloseFailure = { failures.incrementAndGet() })
        cache.use { }
        cache.invalidate()
        cache.use { }
        cache.invalidate()
        assertEquals(2, failures.get())
    }
}
