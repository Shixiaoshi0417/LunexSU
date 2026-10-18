package com.shixiaoshi0417.lunexsu.ui.viewmodel

import android.os.Build
import android.system.Os
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.shixiaoshi0417.lunexsu.BuildConfig
import com.shixiaoshi0417.lunexsu.Natives
import com.shixiaoshi0417.lunexsu.data.HomeMetrics
import com.shixiaoshi0417.lunexsu.data.MetricsHistory
import com.shixiaoshi0417.lunexsu.data.SystemMetricsCollector
import com.shixiaoshi0417.lunexsu.data.repository.SettingsRepository
import com.shixiaoshi0417.lunexsu.data.repository.SettingsRepositoryImpl
import com.shixiaoshi0417.lunexsu.data.zygisk.BuiltinZygisk
import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskOverview
import com.shixiaoshi0417.lunexsu.getKernelVersion
import com.shixiaoshi0417.lunexsu.ksuApp
import com.shixiaoshi0417.lunexsu.ui.screen.home.HomeUiState
import com.shixiaoshi0417.lunexsu.ui.screen.home.SystemInfo
import com.shixiaoshi0417.lunexsu.ui.screen.home.getManagerVersion
import com.shixiaoshi0417.lunexsu.ui.util.checkNewVersion
import com.shixiaoshi0417.lunexsu.ui.util.getSELinuxStatusRaw
import com.shixiaoshi0417.lunexsu.ui.util.module.LatestVersionInfo
import com.shixiaoshi0417.lunexsu.ui.util.resolveDeviceName
import com.shixiaoshi0417.lunexsu.ui.util.rootAvailable

class HomeViewModel(
    private val settingsRepo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Collected only by the visible, authorized homepage; never polls in the background. */
    val zygiskOverview: StateFlow<ZygiskOverview> = flow {
        while (true) {
            val snapshot = try {
                BuiltinZygisk.overview()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ZygiskOverview()
            }
            emit(snapshot)
            delay(5_000L)
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), ZygiskOverview())

    /**
     * Live device/storage metrics for the Focus and Stats home layouts. Polled only while a consumer
     * collects the flow, and stopped a few seconds after the last one leaves.
     */
    val metrics: StateFlow<HomeMetrics> = flow {
        val cpuTemperature = ArrayDeque<Float>()
        val memoryUsage = ArrayDeque<Float>()
        while (true) {
            val storage = SystemMetricsCollector.collectStorageStatus()
            val device = SystemMetricsCollector.collectDeviceStatus(ksuApp)
            device.cpuTemperatureC?.let { cpuTemperature.record(it) }
            if (storage.ramTotalBytes > 0L) memoryUsage.record(storage.ramUsedFraction * 100f)
            emit(
                HomeMetrics(
                    device = device,
                    storage = storage,
                    history = MetricsHistory(
                        cpuTemperature = cpuTemperature.toList(),
                        memoryUsage = memoryUsage.toList(),
                    ),
                )
            )
            delay(METRICS_POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(METRICS_STOP_TIMEOUT_MS), HomeMetrics())

    fun refresh() {
        viewModelScope.launch {
            val baseState = withContext(Dispatchers.IO) { buildState() }
            _uiState.update { baseState }
            if (baseState.checkUpdateEnabled) {
                val latestVersionInfo = withContext(Dispatchers.IO) { checkNewVersion() }
                _uiState.update { it.copy(latestVersionInfo = latestVersionInfo) }
            }
        }
    }

    private fun buildState(): HomeUiState {
        val kernelVersion = getKernelVersion()
        val isManager = Natives.isManager
        val ksuVersion = if (isManager) Natives.version else null
        val kernelUAPIVersion = if (isManager) Natives.kernelUAPIVersion else null
        val managerUAPIVersion = Natives.managerUAPIVersion
        val lkmMode = ksuVersion?.let { if (kernelVersion.isGKI()) Natives.isLkmMode else null }
        val isRootAvailable = rootAvailable()
        val managerVersion = getManagerVersion(ksuApp)

        return HomeUiState(
            kernelVersion = kernelVersion,
            ksuVersion = ksuVersion,
            lkmMode = lkmMode,
            isLkmBundled = lkmMode == true && Natives.isLkmBundled,
            isManager = isManager,
            isManagerPrBuild = BuildConfig.IS_PR_BUILD,
            isKernelPrBuild = Natives.isPrBuild,
            requiresNewKernel = isManager && Natives.managerUAPIVersion > Natives.kernelUAPIVersion,
            requiresNewManager = isManager && Natives.managerUAPIVersion < Natives.kernelUAPIVersion,
            kernelUAPIVersion = kernelUAPIVersion,
            managerUAPIVersion = managerUAPIVersion,
            isRootAvailable = isRootAvailable,
            isSafeMode = Natives.isSafeMode,
            isLateLoadMode = Natives.isLateLoadMode,
            checkUpdateEnabled = settingsRepo.checkUpdate,
            latestVersionInfo = LatestVersionInfo(),
            currentManagerVersionCode = managerVersion.versionCode,
            systemInfo = SystemInfo(
                kernelVersion = Os.uname().release,
                managerVersion = "${managerVersion.versionName} (${managerVersion.versionCode}-${managerUAPIVersion})",
                deviceModel = resolveDeviceName(),
                fingerprint = Build.FINGERPRINT,
                selinuxStatus = getSELinuxStatusRaw(),
                seccompStatus = runCatching {
                    Os.prctl(21 /* PR_GET_SECCOMP */, 0, 0, 0, 0)
                }.getOrDefault(-1),
            ),
        )
    }
}

/** How often the home screen refreshes its device/storage metrics. */
private const val METRICS_POLL_INTERVAL_MS = 5_000L

/** How long the metrics keep polling after the last collector goes away. */
private const val METRICS_STOP_TIMEOUT_MS = 5_000L

/** How many samples the wave charts of the stats board keep, oldest dropped first. */
private const val METRICS_HISTORY_SIZE = 30

private fun ArrayDeque<Float>.record(value: Float) {
    addLast(value)
    while (size > METRICS_HISTORY_SIZE) removeFirst()
}
