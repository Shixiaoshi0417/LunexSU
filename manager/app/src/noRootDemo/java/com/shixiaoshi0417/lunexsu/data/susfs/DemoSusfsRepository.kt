// UI model adapted from FinaSU (GPL-3.0); no SUSFS engine or kernel interface is used.
package com.shixiaoshi0417.lunexsu.data.susfs

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class SusfsStatus(
    val protocol: String = "probing", val version: String = "", val implementation: String = "unknown",
    val variant: String = "", val features: List<String> = emptyList(), val procNodes: List<String> = emptyList(),
    val management: String = "saved", val automatic: Boolean = false, val error: String? = null,
) {
    val detected get() = protocol == "compatible" || protocol == "unknown_protocol"
    val writable get() = protocol == "compatible" && error == null
    fun supports(feature: String) = writable && "CONFIG_KSU_SUSFS_$feature" in features
    fun proc(node: String) = writable && implementation == "lkm" && node in procNodes
    companion object {
        fun parse(text: String): SusfsStatus {
            val json = JSONObject(text)
            val detection = json.getJSONObject("detection")
            fun strings(key: String) = detection.getJSONArray(key).let { a -> List(a.length()) { a.getString(it) } }
            return SusfsStatus(detection.getString("status"), detection.getString("version"),
                detection.getString("implementation"), detection.getString("variant"), strings("features"),
                strings("proc_nodes"), json.getString("management"), json.getBoolean("automatic"),
                if (json.isNull("config_error")) null else json.getString("config_error"))
        }
    }
}

/** Editing affects only this process-local configuration. Applying only previews a receipt. */
class SusfsRepository(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    companion object {
        private val mutex = Mutex()
        private var text = """{"schema":1,"automatic":false,"settings":{},"rules":[],"lkm":{"hidden_modules":[],"mount_prefixes":[]}}"""
    }
    suspend fun status(): SusfsStatus = mutex.withLock {
        SusfsStatus("compatible", "v2.3.0 (DEMO)", "lkm", "GKI", listOf("SUS_PATH", "SUS_MAP", "SUS_KSTAT",
            "SUS_MOUNT", "ENABLE_LOG", "SPOOF_UNAME", "SPOOF_CMDLINE_OR_BOOTCONFIG", "OPEN_REDIRECT")
            .map { "CONFIG_KSU_SUSFS_$it" }, listOf("susfs_path", "susfs_kstat", "susfs_open_redirect",
                "susfs_enable_log", "susfs_avc_spoof", "susfs_hide_modules", "susfs_hide_mounts"),
            automatic = JSONObject(text).getBoolean("automatic"))
    }
    suspend fun export(): String = mutex.withLock { text }
    suspend fun save(value: String): String = mutex.withLock {
        text = SusfsInputs.preview(value).toString()
        "{\"status\":\"saved_demo_only\"}"
    }
    suspend fun apply(): String = """{"status":"DEMO: no kernel changes","applied":[],"failures":{},"pending_reboot":[]}"""
}
