package com.shixiaoshi0417.lunexsu.data.sui

import org.json.JSONObject

enum class SuiPermission(val flag: Int) {
    ASK(0), ROOT(2), DENY(4), HIDDEN(8), SHELL(16);

    companion object {
        const val MASK = 30
        fun fromFlags(flags: Int): SuiPermission = entries.firstOrNull { it.flag == flags } ?: DENY
    }
}

/** A module prerequisite, not proof that Zygote injection or the Sui service is healthy. */
data class SuiZygiskStatus(val state: String = "unavailable", val providers: List<String> = emptyList()) {
    val ready: Boolean
        get() = state == "ready" && providers.isNotEmpty() && providers.size <= 2 &&
            providers.distinct().size == providers.size && providers.all { it in KNOWN_IDS }

    companion object {
        private val KNOWN_IDS = setOf("rezygisk", "zygisksu")
        internal fun parse(json: JSONObject?): SuiZygiskStatus {
            if (json == null) return SuiZygiskStatus()
            val state = json.opt("state") as? String ?: return SuiZygiskStatus()
            if (state !in setOf("ready", "missing", "pending_reboot", "conflict", "unavailable")) return SuiZygiskStatus()
            val ids = json.optJSONArray("providers") ?: return SuiZygiskStatus()
            if (ids.length() > 2) return SuiZygiskStatus()
            val providers = (0 until ids.length()).map { ids.opt(it) as? String ?: return SuiZygiskStatus() }
            if (providers.any { it !in KNOWN_IDS } || providers.distinct().size != providers.size ||
                (state == "ready" && providers.isEmpty())) return SuiZygiskStatus()
            return SuiZygiskStatus(state, providers)
        }
    }
}

data class SuiStatus(
    val state: String = "unavailable",
    val payloadAvailable: Boolean = false,
    val desiredEnabled: Boolean = false,
    val pendingReboot: Boolean = false,
    val serviceHealthy: Boolean = false,
    val version: String = "",
    val reason: String = "",
    val zygisk: SuiZygiskStatus = SuiZygiskStatus(),
) {
    companion object {
        fun parse(text: String): SuiStatus {
            require(text.length <= 128 * 1024) { "Oversized Sui response" }
            val json = JSONObject(text)
            require(json.getInt("schema_version") == 1) { "Unsupported Sui response" }
            val runtime = json.optJSONObject("runtime")
            val state = json.getString("state")
            require(state in setOf("disabled", "pending_reboot", "enabled", "unavailable"))
            return SuiStatus(
                state, json.getBoolean("payload_available"), runtime?.optBoolean("desired_enabled") == true,
                runtime?.optBoolean("pending_reboot") == true, runtime?.optBoolean("service_healthy") == true,
                json.optString("payload_version", ""), json.optString("reason", ""),
                SuiZygiskStatus.parse(json.optJSONObject("zygisk")),
            )
        }
    }
}

data class SuiApp(val uid: Int, val packages: String, val label: String, val permission: SuiPermission,
    val identity: String = "")

data class SuiUiState(
    val status: SuiStatus = SuiStatus(),
    val apps: List<SuiApp> = emptyList(),
    /** A verified management connection. Requests are answered by Sui's SystemUI dialog, not here. */
    val connected: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val reconciling: Boolean = false,
) {
    /** Like the built-in Zygisk switch, the checked state is the next-boot setting. */
    val switchChecked: Boolean get() = status.desiredEnabled

    val switchEnabled: Boolean
        get() = !busy && !reconciling && (status.desiredEnabled || status.payloadAvailable)

    /** A confirmation only applies while it still changes the next-boot setting. */
    fun canSwitchTo(enabled: Boolean): Boolean = switchEnabled && status.desiredEnabled != enabled &&
        (!enabled || status.zygisk.ready)

    /** Permission management is offered only once Sui is switched on. */
    val managementVisible: Boolean get() = status.desiredEnabled
}
