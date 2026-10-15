package com.shixiaoshi0417.lunexsu.ui.viewmodel

import android.system.OsConstants
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.shixiaoshi0417.lunexsu.Natives
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.repository.SettingsRepository
import com.shixiaoshi0417.lunexsu.data.repository.SettingsRepositoryImpl
import com.shixiaoshi0417.lunexsu.data.zygisk.BuiltinZygisk
import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskApplyResult
import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskBackend
import com.shixiaoshi0417.lunexsu.ui.util.reboot
import com.shixiaoshi0417.lunexsu.ksuApp
import com.shixiaoshi0417.lunexsu.ui.screen.settings.SettingsUiState
import com.shixiaoshi0417.lunexsu.ui.theme.ColorContrast
import com.shixiaoshi0417.lunexsu.ui.theme.ColorGenerationMode
import com.shixiaoshi0417.lunexsu.ui.theme.ColorMode
import com.shixiaoshi0417.lunexsu.ui.theme.ThemeController

class SettingsViewModel(
    private val repo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            // Applies the legacy AMOLED color-mode migration before the values are read.
            ThemeController.getAppSettings(repo)
            val checkUpdate = repo.checkUpdate
            val checkModuleUpdate = repo.checkModuleUpdate
            val themeMode = repo.themeMode
            val miuixMonet = repo.miuixMonet
            val keyColor = repo.keyColor
            val enablePredictiveBack = repo.enablePredictiveBack
            val enableSwipeDismiss = repo.enableSwipeDismiss
            val pagerInterceptionMode = repo.pagerInterceptionMode
            val enableBlur = repo.enableBlur
            val enableFloatingBottomBar = repo.enableFloatingBottomBar
            val enableFloatingBottomBarBlur = repo.enableFloatingBottomBarBlur
            val enableNavigationBadge = repo.enableNavigationBadge
            val pageScale = repo.pageScale
            val moduleDescriptionMaxLines = repo.moduleDescriptionMaxLines
            val enableWebDebugging = repo.enableWebDebugging
            val colorStyle = repo.colorStyle
            val colorSpec = repo.colorSpec
            val amoledBlack = repo.amoledBlack
            val useDynamicColor = repo.useDynamicColor
            val colorContrast = repo.colorContrast
            val colorGenerationMode = repo.colorGenerationMode
            val homeLayoutStyle = repo.homeLayoutStyle
            val statsTopLayout = repo.statsTopLayout
            val homeHideWorkingMode = repo.homeHideWorkingMode
            val isLkmMode = repo.isLkmMode()

            // Async loading for natives/features
            val suCompatStatus = repo.getSuCompatStatus()
            val suCompatPersistValue = repo.getSuCompatPersistValue()
            val isSuEnabled = repo.isSuEnabled()

            // Forced features show the value in effect, the persisted one is ignored
            val suCompatMode = when {
                suCompatStatus == "forced" -> if (isSuEnabled) 0 else 2
                suCompatPersistValue == 0L -> 2
                !isSuEnabled -> 1
                else -> 0
            }

            val kernelUmountStatus = repo.getKernelUmountStatus()
            val isKernelUmountEnabled = repo.isKernelUmountEnabled()
            val selinuxHideStatus = repo.getSelinuxHideStatus()
            val isSelinuxHideEnabled = repo.isSelinuxHideEnabled()
            val sulogStatus = repo.getSulogStatus()
            val isSulogEnabled = if (sulogStatus == "forced") {
                repo.getSulogValue() == 1L
            } else {
                repo.getSulogPersistValue() == 1L
            }
            val adbRootStatus = repo.getAdbRootStatus()
            val isAdbRootEnabled = if (adbRootStatus == "forced") {
                repo.getAdbRootValue() == 1L
            } else {
                repo.getAdbRootPersistValue() == 1L
            }
            val shellSuAdbOnlyStatus = repo.getShellSuAdbOnlyStatus()
            val isShellSuAdbOnlyEnabled = if (shellSuAdbOnlyStatus == "forced") {
                repo.getShellSuAdbOnlyValue() == 1L
            } else {
                repo.getShellSuAdbOnlyPersistValue() == 1L
            }
            val isDefaultUmountModules = repo.isDefaultUmountModules()
            val uiMode = repo.uiMode
            val autoJailbreak = repo.autoJailbreak
            val useSoftReboot = repo.useSoftReboot
            val isLateLoadMode = Natives.isLateLoadMode
            val zygiskStatus = BuiltinZygisk.status()

            _uiState.update {
                it.copy(
                    uiMode = uiMode,
                    checkUpdate = checkUpdate,
                    checkModuleUpdate = checkModuleUpdate,
                    themeMode = themeMode,
                    miuixMonet = miuixMonet,
                    keyColor = keyColor,
                    enablePredictiveBack = enablePredictiveBack,
                    enableSwipeDismiss = enableSwipeDismiss,
                    pagerInterceptionMode = pagerInterceptionMode,
                    enableBlur = enableBlur,
                    enableFloatingBottomBar = enableFloatingBottomBar,
                    enableFloatingBottomBarBlur = enableFloatingBottomBarBlur,
                    enableNavigationBadge = enableNavigationBadge,
                    pageScale = pageScale,
                    moduleDescriptionMaxLines = moduleDescriptionMaxLines,
                    enableWebDebugging = enableWebDebugging,
                    colorStyle = colorStyle,
                    colorSpec = colorSpec,
                    amoledBlack = amoledBlack,
                    useDynamicColor = useDynamicColor,
                    colorContrast = colorContrast,
                    colorGenerationMode = colorGenerationMode,
                    homeLayoutStyle = homeLayoutStyle,
                    statsTopLayout = statsTopLayout,
                    homeHideWorkingMode = homeHideWorkingMode,
                    suCompatStatus = suCompatStatus,
                    suCompatMode = suCompatMode,
                    isSuEnabled = isSuEnabled,
                    adbRootStatus = adbRootStatus,
                    isAdbRootEnabled = isAdbRootEnabled,
                    shellSuAdbOnlyStatus = shellSuAdbOnlyStatus,
                    isShellSuAdbOnlyEnabled = isShellSuAdbOnlyEnabled,
                    kernelUmountStatus = kernelUmountStatus,
                    isKernelUmountEnabled = isKernelUmountEnabled,
                    selinuxHideStatus = selinuxHideStatus,
                    isSelinuxHideEnabled = isSelinuxHideEnabled,
                    sulogStatus = sulogStatus,
                    isSulogEnabled = isSulogEnabled,
                    isDefaultUmountModules = isDefaultUmountModules,
                    isLkmMode = isLkmMode,
                    autoJailbreak = autoJailbreak,
                    useSoftReboot = useSoftReboot,
                    isLateLoadMode = isLateLoadMode,
                    zygiskStatus = zygiskStatus,
                    zygiskBackends = BuiltinZygisk.supportedBackends,
                )
            }
        }
    }

    fun setCheckUpdate(enabled: Boolean) {
        repo.checkUpdate = enabled
        _uiState.update { it.copy(checkUpdate = enabled) }
    }

    fun setUiMode(mode: String) {
        val oldMode = repo.uiMode
        val currentThemeMode = repo.themeMode

        val newThemeMode = when (oldMode) {
            "material" if mode == "miuix" -> {
                val colorMode = ColorMode.fromValue(currentThemeMode)
                val baseMode = if (colorMode == ColorMode.DARK_AMOLED) 2 else currentThemeMode
                if (repo.miuixMonet && !colorMode.isMonet) {
                    ColorMode.fromValue(baseMode).toMonetMode()
                } else if (!repo.miuixMonet && colorMode.isMonet) {
                    ColorMode.fromValue(baseMode).toNonMonetMode()
                } else baseMode
            }

            "miuix" if mode == "material" -> {
                val colorMode = ColorMode.fromValue(currentThemeMode)
                if (colorMode.isMonet) {
                    colorMode.toNonMonetMode()
                } else currentThemeMode
            }

            else -> currentThemeMode
        }

        repo.uiMode = mode
        repo.themeMode = newThemeMode
        _uiState.update { it.copy(uiMode = mode, themeMode = newThemeMode) }
    }

    fun setCheckModuleUpdate(enabled: Boolean) {
        repo.checkModuleUpdate = enabled
        _uiState.update { it.copy(checkModuleUpdate = enabled) }
    }

    fun setThemeMode(mode: Int) {
        val currentUiMode = repo.uiMode
        val effectiveMode = if (currentUiMode == "miuix" && _uiState.value.miuixMonet) {
            mode + 3
        } else {
            mode
        }
        repo.themeMode = effectiveMode
        _uiState.update { it.copy(themeMode = effectiveMode) }
    }

    fun setColorMode(mode: ColorMode) {
        repo.themeMode = mode.value
        _uiState.update { it.copy(themeMode = mode.value) }
    }

    fun setMiuixMonet(enabled: Boolean) {
        val currentThemeMode = repo.themeMode
        val colorMode = ColorMode.fromValue(currentThemeMode)
        val newThemeMode = if (enabled) {
            if (!colorMode.isMonet) colorMode.toMonetMode() else currentThemeMode
        } else {
            if (colorMode.isMonet) colorMode.toNonMonetMode() else currentThemeMode
        }
        repo.miuixMonet = enabled
        repo.themeMode = newThemeMode
        _uiState.update { it.copy(miuixMonet = enabled, themeMode = newThemeMode) }
    }

    fun setKeyColor(color: Int) {
        repo.keyColor = color
        _uiState.update { it.copy(keyColor = color) }
    }

    /** Material: picking a key color turns Monet off so the picked color is what the app shows. */
    fun selectKeyColor(color: Int) {
        repo.keyColor = color
        repo.useDynamicColor = false
        _uiState.update { it.copy(keyColor = color, useDynamicColor = false) }
    }

    /** Clears the custom key color and returns to the system Monet palette. */
    fun resetKeyColor() {
        repo.keyColor = 0
        repo.useDynamicColor = true
        _uiState.update { it.copy(keyColor = 0, useDynamicColor = true) }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        repo.useDynamicColor = enabled
        _uiState.update { it.copy(useDynamicColor = enabled) }
    }

    fun setAmoledBlack(enabled: Boolean) {
        repo.amoledBlack = enabled
        _uiState.update { it.copy(amoledBlack = enabled) }
    }

    fun setColorContrast(contrast: ColorContrast) {
        repo.colorContrast = contrast.name
        repo.colorGenerationMode = ColorGenerationMode.CUSTOM
        _uiState.update { it.copy(colorContrast = contrast.name, colorGenerationMode = ColorGenerationMode.CUSTOM) }
    }

    fun setHomeLayoutStyle(style: String) {
        repo.homeLayoutStyle = style
        _uiState.update { it.copy(homeLayoutStyle = repo.homeLayoutStyle) }
    }

    fun setStatsTopLayout(layout: String) {
        repo.statsTopLayout = layout
        _uiState.update { it.copy(statsTopLayout = repo.statsTopLayout) }
    }

    fun setHomeHideWorkingMode(hidden: Boolean) {
        repo.homeHideWorkingMode = hidden
        _uiState.update { it.copy(homeHideWorkingMode = hidden) }
    }

    // Picking a palette style, spec or contrast asks for a regenerated scheme, so it leaves the
    // classic palette an imported theme pack applied (as in FinaSU).
    fun setColorStyle(style: String) {
        repo.colorStyle = style
        repo.colorGenerationMode = ColorGenerationMode.CUSTOM
        _uiState.update { it.copy(colorStyle = style, colorGenerationMode = ColorGenerationMode.CUSTOM) }
    }

    fun setColorSpec(spec: String) {
        repo.colorSpec = spec
        repo.colorGenerationMode = ColorGenerationMode.CUSTOM
        _uiState.update { it.copy(colorSpec = spec, colorGenerationMode = ColorGenerationMode.CUSTOM) }
    }

    fun setEnablePredictiveBack(enabled: Boolean) {
        repo.enablePredictiveBack = enabled
        _uiState.update { it.copy(enablePredictiveBack = enabled) }
    }

    fun setEnableSwipeDismiss(enabled: Boolean) {
        repo.enableSwipeDismiss = enabled
        _uiState.update { it.copy(enableSwipeDismiss = enabled) }
    }

    fun setPagerInterceptionMode(mode: Int) {
        repo.pagerInterceptionMode = mode
        _uiState.update { it.copy(pagerInterceptionMode = mode.coerceIn(0, 2)) }
    }

    fun setEnableBlur(enabled: Boolean) {
        repo.enableBlur = enabled
        _uiState.update { it.copy(enableBlur = enabled) }
    }

    fun setEnableFloatingBottomBar(enabled: Boolean) {
        repo.enableFloatingBottomBar = enabled
        _uiState.update { it.copy(enableFloatingBottomBar = enabled) }
    }

    fun setEnableFloatingBottomBarBlur(enabled: Boolean) {
        repo.enableFloatingBottomBarBlur = enabled
        _uiState.update { it.copy(enableFloatingBottomBarBlur = enabled) }
    }

    fun setEnableNavigationBadge(enabled: Boolean) {
        repo.enableNavigationBadge = enabled
        _uiState.update { it.copy(enableNavigationBadge = enabled) }
    }

    fun setPageScale(scale: Float) {
        repo.pageScale = scale
        _uiState.update { it.copy(pageScale = scale) }
    }

    fun setModuleDescriptionMaxLines(lines: Int) {
        repo.moduleDescriptionMaxLines = lines
        _uiState.update { it.copy(moduleDescriptionMaxLines = lines) }
    }

    fun setEnableWebDebugging(enabled: Boolean) {
        repo.enableWebDebugging = enabled
        _uiState.update { it.copy(enableWebDebugging = enabled) }
    }

    fun setSuCompatMode(mode: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            when (mode) {
                0 -> if (repo.setSuEnabled(true)) {
                    repo.execKsudFeatureSave()
                    repo.setSuCompatModePref(0)
                    _uiState.update { it.copy(suCompatMode = 0, isSuEnabled = true) }
                }

                1 -> if (repo.setSuEnabled(true)) {
                    repo.execKsudFeatureSave()
                    if (repo.setSuEnabled(false)) {
                        // "Disable until reboot" implies it should be enabled on next boot.
                        // We set the preference to 0 (Enabled) to match the persistent state.
                        repo.setSuCompatModePref(0)
                        _uiState.update { it.copy(suCompatMode = 1, isSuEnabled = false) }
                    }
                }

                2 -> if (repo.setSuEnabled(false)) {
                    repo.execKsudFeatureSave()
                    repo.setSuCompatModePref(2)
                    _uiState.update { it.copy(suCompatMode = 2, isSuEnabled = false) }
                }
            }
        }
    }

    fun setKernelUmountEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repo.setKernelUmountEnabled(enabled)) {
                repo.execKsudFeatureSave()
                _uiState.update { it.copy(isKernelUmountEnabled = enabled) }
            }
        }
    }

    fun setSelinuxHideEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val status = repo.setSelinuxHideEnabled(enabled)
            repo.execKsudFeatureSave()
            _uiState.update { it.copy(isSelinuxHideEnabled = enabled) }
            when (status) {
                0 -> {}
                -OsConstants.EAGAIN -> {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ksuApp, R.string.settings_selinux_hide_reboot_required,
                            Toast.LENGTH_LONG).show()
                    }
                }
                else -> {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ksuApp, ksuApp.getString(R.string.settings_selinux_hide_failed, status),
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun setAutoJailbreak(enabled: Boolean) {
        repo.autoJailbreak = enabled
        _uiState.update { it.copy(autoJailbreak = enabled) }
    }

    fun setUseSoftReboot(enabled: Boolean) {
        repo.useSoftReboot = enabled
        _uiState.update { it.copy(useSoftReboot = enabled) }
    }

    fun setSulogEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repo.setSulogEnabled(enabled)) {
                repo.execKsudFeatureSave()
                _uiState.update { it.copy(isSulogEnabled = enabled) }
            }
        }
    }

    fun setShellSuAdbOnlyEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repo.setShellSuAdbOnlyEnabled(enabled)) {
                repo.execKsudFeatureSave()
                _uiState.update { it.copy(isShellSuAdbOnlyEnabled = enabled) }
            }
        }
    }

    fun setAdbRootEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repo.setAdbRootEnabled(enabled)) {
                repo.execKsudFeatureSave()
                _uiState.update { it.copy(isAdbRootEnabled = enabled) }
            }
        }
    }

    fun setZygiskEnabled(enabled: Boolean) {
        if (!_uiState.value.zygiskSwitchEnabled) return
        applyZygisk(if (enabled) BuiltinZygisk.defaultBackend ?: return else null)
    }

    fun setZygiskBackend(backend: ZygiskBackend) {
        val state = _uiState.value
        if (state.zygiskBackendEnabled) applyZygisk(backend, state.zygiskNextNext)
    }

    fun setZygiskNextNext(enabled: Boolean) {
        val state = _uiState.value
        if (state.zygiskNextNextEnabled) applyZygisk(state.zygiskStatus?.configured, enabled)
    }

    fun dismissZygiskRebootDialog() {
        _uiState.update { it.copy(showZygiskRebootDialog = false) }
    }

    /** A full reboot: a soft reboot keeps the current Zygote and would not load the new setup. */
    fun rebootForZygisk() {
        dismissZygiskRebootDialog()
        viewModelScope.launch(Dispatchers.IO) { reboot() }
    }

    private fun applyZygisk(target: ZygiskBackend?, nextNext: Boolean = false) {
        if (_uiState.value.zygiskBusy) return
        _uiState.update { it.copy(zygiskBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = BuiltinZygisk.apply(target, nextNext)
            val status = BuiltinZygisk.status()
            // Both backends manage kernel_umount, so its toggle follows the installed modules.
            val kernelUmountStatus = repo.getKernelUmountStatus()
            val isKernelUmountEnabled = repo.isKernelUmountEnabled()
            _uiState.update {
                it.copy(
                    zygiskBusy = false,
                    zygiskStatus = status,
                    kernelUmountStatus = kernelUmountStatus,
                    isKernelUmountEnabled = isKernelUmountEnabled,
                    // Every saved change, including the companion loader, gets the reboot prompt.
                    showZygiskRebootDialog = result is ZygiskApplyResult.Changed,
                )
            }
            val message = when (result) {
                is ZygiskApplyResult.Conflict -> ksuApp.getString(R.string.settings_zygisk_conflict, result.moduleId)
                is ZygiskApplyResult.Failed -> ksuApp.getString(R.string.settings_zygisk_failed, result.message)
                ZygiskApplyResult.LegacyVex -> ksuApp.getString(R.string.settings_zygisk_legacy_vex)
                else -> null
            }
            if (message != null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ksuApp, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun setDefaultUmountModules(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (repo.setDefaultUmountModules(enabled)) {
                _uiState.update { it.copy(isDefaultUmountModules = enabled) }
            }
        }
    }
}
