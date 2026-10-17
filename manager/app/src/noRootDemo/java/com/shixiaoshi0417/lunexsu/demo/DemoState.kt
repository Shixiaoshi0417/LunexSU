package com.shixiaoshi0417.lunexsu.demo

import java.util.concurrent.ConcurrentHashMap

/** Process-local preview state only. Never reads or writes a system/root path. */
object DemoState {
    const val NOTICE = "NO ROOT DEMO: simulated UI only; no command, root grant, module or boot operation was executed."
    val features = ConcurrentHashMap<String, Long>()
    val policies = ConcurrentHashMap<String, String>()
    val templates = ConcurrentHashMap<String, String>()
    val modules = linkedMapOf("demo_theme" to true, "demo_tools" to true, "demo_disabled" to false)
    val removed = mutableSetOf<String>()

    @Synchronized fun moduleRows(): List<Triple<String, Boolean, Boolean>> =
        modules.map { (id, enabled) -> Triple(id, enabled, id in removed) }

    @Synchronized fun toggle(id: String, enabled: Boolean): Boolean {
        if (id !in modules) return false
        modules[id] = enabled
        return true
    }

    @Synchronized fun remove(id: String, removing: Boolean): Boolean {
        if (id !in modules) return false
        if (removing) removed.add(id) else removed.remove(id)
        return true
    }
}
