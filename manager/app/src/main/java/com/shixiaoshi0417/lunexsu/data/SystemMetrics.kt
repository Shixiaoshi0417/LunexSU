// Adapted from FolkSU (https://github.com/LyraVoid/FolkSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.compose.runtime.Immutable
import java.io.File

/**
 * Battery level/temperature and CPU temperature, refreshed on a timer by the home screen.
 *
 * Every field is nullable: a source that is unavailable (no battery broadcast, no readable thermal
 * zone) reports `null` instead of a fabricated zero.
 */
@Immutable
data class DeviceStatus(
    val batteryLevelPercent: Int? = null,
    val batteryTemperatureC: Float? = null,
    val cpuTemperatureC: Float? = null,
    val cpuFrequencies: List<CpuFrequency> = emptyList(),
)

/** One CPU core's clock, in kilohertz, as reported by `/sys/devices/system/cpu/cpuN/cpufreq`. */
@Immutable
data class CpuFrequency(
    val coreIndex: Int,
    val currentFreqKHz: Long,
    val maxFreqKHz: Long,
)

/** Internal storage and memory usage in bytes. A total of 0 means the source was unreadable. */
@Immutable
data class StorageStatus(
    val dataUsedBytes: Long = 0L,
    val dataTotalBytes: Long = 0L,
    val ramUsedBytes: Long = 0L,
    val ramTotalBytes: Long = 0L,
) {
    val ramUsedFraction: Float
        get() = if (ramTotalBytes > 0L) (ramUsedBytes.toFloat() / ramTotalBytes).coerceIn(0f, 1f) else 0f
}

/** The recent samples of the stats board, oldest first, kept only so the wave charts have a curve. */
@Immutable
data class MetricsHistory(
    val cpuTemperature: List<Float> = emptyList(),
    val memoryUsage: List<Float> = emptyList(),
)

/** The live metrics the home screen renders; either half may be absent before the first poll. */
@Immutable
data class HomeMetrics(
    val device: DeviceStatus? = null,
    val storage: StorageStatus? = null,
    val history: MetricsHistory = MetricsHistory(),
)

/**
 * Reads device/storage metrics from the kernel's public interfaces only - no root, no shell:
 *
 * - battery: the sticky `ACTION_BATTERY_CHANGED` broadcast;
 * - CPU temperature: the first `cpu*` thermal zone under `/sys/class/thermal`;
 * - CPU clocks: the `cpufreq` node of every core under `/sys/devices/system/cpu`;
 * - memory: `MemTotal`/`MemAvailable` in `/proc/meminfo`;
 * - internal storage: `statvfs` on the data partition.
 */
object SystemMetricsCollector {

    private const val KILOBYTE = 1024L
    private const val CPU_SYSFS = "/sys/devices/system/cpu"
    private const val CPU_DIR_PREFIX_LENGTH = 3
    private val CPU_DIR_PATTERN = Regex("cpu\\d+")

    fun collectDeviceStatus(context: Context): DeviceStatus {
        val battery = batteryIntent(context)
        return DeviceStatus(
            batteryLevelPercent = battery?.let { readBatteryLevelPercent(it) },
            batteryTemperatureC = battery?.let { readBatteryTemperatureC(it) },
            cpuTemperatureC = readCpuTemperatureC(),
            cpuFrequencies = readCpuFrequencies(),
        )
    }

    fun collectStorageStatus(): StorageStatus {
        val data = readDataUsage()
        val memory = readMemoryUsage()
        return StorageStatus(
            dataUsedBytes = data.first,
            dataTotalBytes = data.second,
            ramUsedBytes = memory.first,
            ramTotalBytes = memory.second,
        )
    }

    private fun batteryIntent(context: Context): Intent? = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    private fun readBatteryLevelPercent(intent: Intent): Int? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100 / scale).coerceIn(0, 100)
    }

    private fun readBatteryTemperatureC(intent: Intent): Float? {
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths == Int.MIN_VALUE) return null
        return tenths / 10f
    }

    /**
     * SoC/CPU temperature in celsius: the first thermal zone named `cpu*`, falling back to a
     * `soc`/`aoss`/`tsens` zone.
     */
    private fun readCpuTemperatureC(): Float? {
        val zones = runCatching { File("/sys/class/thermal").listFiles() }.getOrNull() ?: return null
        val typed = zones
            .filter { it.name.startsWith("thermal_zone") }
            .mapNotNull { zone ->
                val type = runCatching { File(zone, "type").readText().trim() }.getOrNull()
                type?.takeIf { it.isNotEmpty() }?.let { zone to it }
            }
        val zone = typed.firstOrNull { it.second.startsWith("cpu") }
            ?: typed.firstOrNull { it.second.contains("soc") }
            ?: typed.firstOrNull { it.second.contains("aoss") || it.second.contains("tsens") }
            ?: return null
        val raw = runCatching { File(zone.first, "temp").readText().trim().toFloatOrNull() }.getOrNull()
            ?: return null
        return raw / 1000f
    }

    /** The current and maximum clock of every core that exposes a readable `cpufreq` node. */
    private fun readCpuFrequencies(): List<CpuFrequency> {
        val cores = runCatching {
            File(CPU_SYSFS)
                .listFiles { file -> CPU_DIR_PATTERN.matches(file.name) }
                ?.sortedBy { it.name.drop(CPU_DIR_PREFIX_LENGTH).toIntOrNull() ?: Int.MAX_VALUE }
        }.getOrNull() ?: return emptyList()

        return cores.mapNotNull { core ->
            val index = core.name.drop(CPU_DIR_PREFIX_LENGTH).toIntOrNull() ?: return@mapNotNull null
            val cpufreq = File(core, "cpufreq")
            val current = readLong(File(cpufreq, "scaling_cur_freq"))
                ?: readLong(File(cpufreq, "cpuinfo_cur_freq"))
                ?: return@mapNotNull null
            val max = readLong(File(cpufreq, "cpuinfo_max_freq"))
                ?: readLong(File(cpufreq, "scaling_max_freq"))
                ?: current
            CpuFrequency(coreIndex = index, currentFreqKHz = current, maxFreqKHz = max)
        }
    }

    private fun readLong(file: File): Long? =
        runCatching { file.readText().trim().toLongOrNull() }.getOrNull()

    private fun readDataUsage(): Pair<Long, Long> = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val available = stat.availableBlocksLong * stat.blockSizeLong
        (total - available).coerceAtLeast(0L) to total
    }.getOrDefault(0L to 0L)

    private fun readMemoryUsage(): Pair<Long, Long> {
        val values = readMeminfo() ?: return 0L to 0L
        val total = values["MemTotal"] ?: return 0L to 0L
        val available = values["MemAvailable"] ?: values["MemFree"] ?: return 0L to 0L
        return (total - available).coerceAtLeast(0L) to total
    }

    private fun readMeminfo(): Map<String, Long>? = runCatching {
        buildMap {
            File("/proc/meminfo").forEachLine { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) return@forEachLine
                val key = line.substring(0, separator)
                val kb = line.substring(separator + 1).trim().substringBefore(' ').toLongOrNull()
                if (kb != null) put(key, kb * KILOBYTE)
            }
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
