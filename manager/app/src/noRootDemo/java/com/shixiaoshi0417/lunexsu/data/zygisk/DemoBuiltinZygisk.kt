package com.shixiaoshi0417.lunexsu.data.zygisk

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ZygiskApplyResult {
    data object Unchanged : ZygiskApplyResult
    data class Changed(val status: ZygiskStatus) : ZygiskApplyResult
    data class Conflict(val moduleId: String) : ZygiskApplyResult
    data class Failed(val message: String) : ZygiskApplyResult
    data object LegacyVex : ZygiskApplyResult
}

/** In-memory preview. Never installs, enables, loads or reads a real module. */
object BuiltinZygisk {
    private val mutex = Mutex()
    val supportedBackends = ZygiskBackend.entries.toList()
    val defaultBackend: ZygiskBackend? = ZygiskBackend.Default
    private val simulated = MutableStateFlow(ZygiskStatus(configured = defaultBackend, active = defaultBackend, monitorRunning = true))
    val demoState = simulated.asStateFlow()
    suspend fun status() = mutex.withLock { simulated.value }
    suspend fun overview(): ZygiskOverview = mutex.withLock {
        ZygiskOverview(available = true, status = simulated.value, versions = mapOf(
            ZygiskBackend.Re.moduleId to ZygiskModuleVersion(installed = "1.0.0 (DEMO)"),
            ZygiskBackend.Neo.moduleId to ZygiskModuleVersion(installed = "2.4 (DEMO)"),
            ZygiskModule.NextNext.moduleId to ZygiskModuleVersion(installed = "1.12.0 (DEMO)"),
        ))
    }
    suspend fun ownedModuleIds(): Set<String> = emptySet()
    suspend fun apply(target: ZygiskBackend?, nextNext: Boolean = false): ZygiskApplyResult = mutex.withLock {
        val requested = nextNext && target != null
        if (simulated.value.configured == target && simulated.value.nextNextConfigured == requested) return@withLock ZygiskApplyResult.Unchanged
        simulated.value = simulated.value.copy(configured = target, nextNextConfigured = requested)
        ZygiskApplyResult.Changed(simulated.value)
    }
}
