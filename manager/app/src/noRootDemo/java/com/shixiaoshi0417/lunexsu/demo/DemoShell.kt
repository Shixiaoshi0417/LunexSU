package com.shixiaoshi0417.lunexsu.demo

import com.topjohnwu.superuser.Shell
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Not a process or a root shell. Arbitrary jobs and raw stream tasks fail closed. */
class DemoShell : Shell() {
    private var closed = false
    override fun isAlive() = !closed
    override fun getStatus() = if (closed) UNKNOWN else NON_ROOT_SHELL
    override fun close() { closed = true }
    override fun waitAndClose(timeout: Long, unit: TimeUnit): Boolean { close(); return true }
    override fun execTask(task: Task) { throw IOException(DemoState.NOTICE) }
    override fun submitTask(task: Task) { task.shellDied() }
    override fun newJob(): Job = DemoJob()

    private class DemoJob : Job() {
        private var output: MutableList<String>? = null
        private var errors: MutableList<String>? = null
        override fun to(output: MutableList<String>?): Job = to(output, null)
        override fun to(output: MutableList<String>?, errors: MutableList<String>?): Job {
            this.output = output; this.errors = errors; return this
        }
        override fun add(vararg commands: String): Job = this
        override fun add(input: InputStream): Job = this
        override fun exec(): Result {
            errors?.add(DemoState.NOTICE)
            return object : Result() {
                override fun getOut(): List<String> = output.orEmpty()
                override fun getErr(): List<String> = listOf(DemoState.NOTICE)
                override fun getCode() = 126
            }
        }
        override fun submit(executor: Executor?, callback: ResultCallback?) {
            val result = exec()
            if (executor == null) callback?.onResult(result) else executor.execute { callback?.onResult(result) }
        }
        override fun enqueue(): Future<Result> = CompletableFuture.completedFuture(exec())
    }
}
