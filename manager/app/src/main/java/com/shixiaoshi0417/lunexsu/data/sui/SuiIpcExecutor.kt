package com.shixiaoshi0417.lunexsu.data.sui

import java.util.concurrent.Callable
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Binder is not interruptible. Retire timed-out generations without accumulating threads. */
internal class SuiIpcExecutor(
    private val timeoutMillis: Long = 5_000,
    private val maxWorkers: Int = 3,
) {
    internal class Lease {
        private val valid = AtomicBoolean(true)
        fun checkActive() = check(valid.get()) { "Expired Sui IPC worker" }
        fun retire() { valid.set(false) }
    }

    private class Worker(val lease: Lease, val executor: ThreadPoolExecutor)
    private val workers = mutableListOf<Worker>()
    private var current: Worker? = null

    init { require(timeoutMillis > 0 && maxWorkers > 0) }

    @Synchronized private fun worker(): Worker {
        workers.removeAll { it.executor.isTerminated }
        current?.let { return it }
        check(workers.size < maxWorkers) { "Sui IPC workers are still unresponsive; retry after they recover" }
        return Worker(Lease(), ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, SynchronousQueue()) {
            Thread(it, "SuiManagerIPC").apply { isDaemon = true }
        }.apply {
            // Future.get can finish just before the worker returns to its handoff queue.
            // Wait only for that handoff; no buffered jobs and no retry of an accepted RPC.
            rejectedExecutionHandler = RejectedExecutionHandler { task, executor ->
                if (executor.isShutdown || !executor.queue.offer(task, 50, TimeUnit.MILLISECONDS)) {
                    throw RejectedExecutionException("Sui IPC worker is busy")
                }
            }
        }).also { current = it; workers += it }
    }

    @Synchronized private fun retire(worker: Worker) {
        worker.lease.retire()
        worker.executor.shutdownNow()
        if (current === worker) current = null
    }

    fun <T> call(block: (Lease) -> T): T {
        val worker = worker()
        val future = try {
            worker.executor.submit(Callable {
                worker.lease.checkActive()
                val value = block(worker.lease)
                worker.lease.checkActive()
                value
            })
        } catch (error: RejectedExecutionException) {
            throw IllegalStateException("Sui IPC is busy; no call was queued", error)
        }
        return try { future.get(timeoutMillis, TimeUnit.MILLISECONDS) }
        catch (error: TimeoutException) {
            retire(worker)
            throw error
        } catch (error: InterruptedException) {
            retire(worker)
            Thread.currentThread().interrupt()
            throw error
        } finally { if (!future.isDone) future.cancel(true) }
    }

    @Synchronized internal fun workerCount(): Int = workers.count { !it.executor.isTerminated }
    @Synchronized internal fun close() { workers.toList().forEach(::retire) }
}
