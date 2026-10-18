package com.shixiaoshi0417.lunexsu.data.sui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-memory UI simulation only. No Binder, daemon, process, file or root operations. */
object SuiManager {
    private val apps = listOf(SuiApp(10101, "demo.terminal", "Demo Terminal", SuiPermission.ASK),
        SuiApp(10102, "demo.files", "Demo Files", SuiPermission.DENY))
    private val mutable = MutableStateFlow(SuiUiState(status = SuiStatus(
        state = "disabled", payloadAvailable = true, version = "13.5.4.3 (DEMO)"), apps = apps))
    val state = mutable.asStateFlow()
    fun start(@Suppress("UNUSED_PARAMETER") application: Context) = Unit
    fun onForeground() = Unit
    fun refresh(@Suppress("UNUSED_PARAMETER") includeApps: Boolean = true) {
        mutable.update { current ->
            if (!current.status.pendingReboot) current else current.copy(
                status = current.status.copy(state = "enabled", pendingReboot = false, serviceHealthy = true),
                hostAttached = true, requests = listOf(SuiRequest(1, 10101, 1234, "demo.terminal", 1, 0)))
        }
    }
    fun prepare() { mutable.update { it.copy(error = null) } }
    fun enable() { mutable.update { it.copy(status = it.status.copy(state = "pending_reboot",
        desiredEnabled = true, pendingReboot = true), error = null) } }
    fun disable() { mutable.update { it.copy(status = it.status.copy(state = "disabled",
        desiredEnabled = false, pendingReboot = false, serviceHealthy = false), hostAttached = false, requests = emptyList()) } }
    fun setPermission(selected: SuiApp, permission: SuiPermission) {
        mutable.update { it.copy(apps = it.apps.map { app -> if (app.uid == selected.uid) app.copy(permission = permission) else app }) }
    }
    fun decide(request: SuiRequest, decision: SuiDecision) {
        mutable.update { current -> current.copy(requests = current.requests.filterNot { it == request },
            apps = current.apps.map {
                if (it.uid != request.uid || decision.once) it else it.copy(permission =
                    if (!decision.allowed) SuiPermission.DENY else if (decision.shell) SuiPermission.SHELL else SuiPermission.ROOT)
            }) }
    }
}
