package com.shixiaoshi0417.lunexsu.ui.viewmodel

import com.shixiaoshi0417.lunexsu.data.model.Module
import com.shixiaoshi0417.lunexsu.data.model.ModuleUpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull

/** Cache successful checks only; a cancelled owner cannot suppress its replacement. */
internal class ModuleUpdateChecks(
    private val now: () -> Long,
    private val ttlMillis: Long = 15 * 60 * 1000L,
    private val timeoutMillis: Long = 5_000L,
) {
    private data class Signature(
        val url: String, val version: Int, val enabled: Boolean, val update: Boolean, val remove: Boolean,
    )
    private data class Entry(val signature: Signature, val info: ModuleUpdateInfo, val checkedAt: Long)
    private class Request(val module: Module, val signature: Signature, val owner: Job)

    private val cache = mutableMapOf<String, Entry>()
    private val inFlight = mutableMapOf<String, Request>()

    suspend fun sync(
        modules: List<Module>,
        force: Boolean = false,
        fetch: suspend (Module) -> Result<ModuleUpdateInfo>,
    ): Map<String, ModuleUpdateInfo> = coroutineScope {
        val owner = currentCoroutineContext().job
        val requests = synchronized(this@ModuleUpdateChecks) {
            val ids = modules.map { it.id }.toSet()
            cache.keys.retainAll(ids)
            inFlight.keys.retainAll(ids)
            modules.mapNotNull { module ->
                val signature = Signature(module.updateJson, module.versionCode, module.enabled, module.update, module.remove)
                val cached = cache[module.id]
                val age = cached?.let { now() - it.checkedAt }
                val fresh = cached?.signature == signature && age != null && age >= 0 && age < ttlMillis
                val running = inFlight[module.id]
                when {
                    !force && fresh -> null
                    !force && running?.signature == signature && running.owner.isActive -> null
                    else -> {
                        cache.remove(module.id)
                        Request(module, signature, owner).also { inFlight[module.id] = it }
                    }
                }
            }
        }
        try {
            requests.map { request ->
                async {
                    val info = try {
                        withTimeoutOrNull(timeoutMillis) { fetch(request.module).getOrThrow() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    currentCoroutineContext().ensureActive()
                    synchronized(this@ModuleUpdateChecks) {
                        if (inFlight[request.module.id] === request && info != null) {
                            cache[request.module.id] = Entry(request.signature, info, now())
                        }
                    }
                }
            }.awaitAll()
        } finally {
            // Non-suspending cleanup also runs after cancellation, even before an async body starts.
            synchronized(this@ModuleUpdateChecks) {
                requests.forEach { request ->
                    if (inFlight[request.module.id] === request) inFlight.remove(request.module.id)
                }
            }
        }
        synchronized(this@ModuleUpdateChecks) { cache.mapValues { it.value.info } }
    }
}
