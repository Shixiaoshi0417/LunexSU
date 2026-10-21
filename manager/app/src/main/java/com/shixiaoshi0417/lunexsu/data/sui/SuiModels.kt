package com.shixiaoshi0417.lunexsu.data.sui

import org.json.JSONObject

enum class SuiPermission(val flag: Int) {
    ASK(0), ROOT(2), DENY(4), HIDDEN(8), SHELL(16);

    companion object {
        const val MASK = 30
        fun fromFlags(flags: Int): SuiPermission = entries.firstOrNull { it.flag == flags } ?: DENY
    }
}

enum class SuiDecision(val allowed: Boolean, val once: Boolean, val shell: Boolean) {
    ROOT(true, false, false), ROOT_ONCE(true, true, false),
    SHELL(true, false, true),
    DENY(false, false, false), DENY_ONCE(false, true, false),
}

data class SuiStatus(
    val state: String = "unavailable",
    val payloadAvailable: Boolean = false,
    val desiredEnabled: Boolean = false,
    val pendingReboot: Boolean = false,
    val serviceHealthy: Boolean = false,
    val version: String = "",
    val reason: String = "",
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
            )
        }
    }
}

data class SuiApp(val uid: Int, val packages: String, val label: String, val permission: SuiPermission,
    val identity: String = "")

data class SuiRequest(
    val connection: Long,
    val uid: Int,
    val pid: Int,
    val packageName: String,
    val requestCode: Int,
    val receivedAt: Long,
    val identity: String = "",
) {
    fun expired(now: Long, timeoutMillis: Long = 60_000): Boolean =
        receivedAt < 0 || now < receivedAt || now - receivedAt >= timeoutMillis
}

/** No overflow, timeout or dismissal ever implicitly grants a capability. */
class SuiRequestQueue(private val capacity: Int = 16, private val timeoutMillis: Long = 60_000) {
    private val requests = mutableListOf<SuiRequest>()
    private val replying = mutableMapOf<SuiRequest, Any>()
    @Synchronized fun snapshot(): List<SuiRequest> = requests.filter { it !in replying }
    @Synchronized fun offer(request: SuiRequest): Boolean {
        if (request.uid < 0 || request.pid <= 0 || request.packageName.length !in 1..255 ||
            !request.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*"))) return false
        if ((requests + replying.keys).any { it.connection == request.connection && it.uid == request.uid &&
                it.pid == request.pid && it.requestCode == request.requestCode }) return false
        if (requests.size >= capacity) return false
        requests += request
        return true
    }
    /** The synchronous host callback must fail before accepting a request it cannot retain. */
    internal fun requireOffer(request: SuiRequest) {
        check(request.identity.isNotEmpty() && offer(request)) { "Sui permission request was not accepted" }
    }
    @Synchronized fun remove(request: SuiRequest): Boolean = requests.remove(request)
    // Expiry is a denial candidate, not proof that its reply was sent.
    @Synchronized fun expire(now: Long): List<SuiRequest> =
        requests.filter { it !in replying && it.expired(now, timeoutMillis) }

    /** Keep unsent failures retryable; consume immediately before the RPC can be sent. */
    internal fun respond(request: SuiRequest, reply: (() -> Unit) -> Unit): Boolean {
        val attempt = Any()
        synchronized(this) {
            if (request !in requests || request in replying) return false
            replying[request] = attempt
        }
        try {
            reply {
                synchronized(this) {
                    check(replying[request] === attempt && requests.remove(request)) { "Expired Sui reply attempt" }
                }
            }
            return true
        } finally {
            synchronized(this) {
                // Invalidate even a late, unsent worker before another attempt can claim this request.
                if (replying[request] === attempt) replying.remove(request)
            }
        }
    }

    @Synchronized fun clear(): List<SuiRequest> = requests.toList().also {
        requests.clear()
        replying.clear()
    }
}

data class SuiUiState(
    val status: SuiStatus = SuiStatus(),
    val apps: List<SuiApp> = emptyList(),
    val requests: List<SuiRequest> = emptyList(),
    val hostAttached: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val reconciling: Boolean = false,
)
