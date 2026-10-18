package com.shixiaoshi0417.lunexsu.ui.screen.settings

import androidx.compose.runtime.Immutable
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.shixiaoshi0417.lunexsu.data.model.HomeLayoutStyle
import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskBackend
import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskStatus
import com.shixiaoshi0417.lunexsu.ui.UiMode
import com.shixiaoshi0417.lunexsu.ui.theme.ColorContrast
import com.shixiaoshi0417.lunexsu.ui.theme.ColorGenerationMode

@Immutable
data class SettingsUiState(
    val uiMode: String = UiMode.DEFAULT_VALUE,
    val checkUpdate: Boolean = true,
    val checkModuleUpdate: Boolean = true,
    val themeMode: Int = 0,
    val miuixMonet: Boolean = false,
    val keyColor: Int = 0,
    val colorStyle: String = PaletteStyle.TonalSpot.name,
    val colorSpec: String = ColorSpec.SpecVersion.SPEC_2025.name,
    val amoledBlack: Boolean = false,
    val useDynamicColor: Boolean = true,
    val colorContrast: String = ColorContrast.STANDARD.name,
    val colorGenerationMode: String = ColorGenerationMode.CUSTOM,
    val homeLayoutStyle: String = HomeLayoutStyle.DEFAULT,
    val statsTopLayout: String = HomeLayoutStyle.STATS_TOP_STATUS,
    val homeHideWorkingMode: Boolean = false,
    val enablePredictiveBack: Boolean = false,
    val enableSwipeDismiss: Boolean = true,
    val pagerInterceptionMode: Int = 1,
    val enableBlur: Boolean = true,
    val enableFloatingBottomBar: Boolean = false,
    val enableFloatingBottomBarBlur: Boolean = false,
    val enableNavigationBadge: Boolean = true,
    val pageScale: Float = 1.0f,
    val moduleDescriptionMaxLines: Int = 4,
    val enableWebDebugging: Boolean = false,

    // Su Compat
    val suCompatStatus: String = "",
    val suCompatMode: Int = 0, // 0: enable default, 1: disable until reboot, 2: disable always
    val isSuEnabled: Boolean = false,

    // Kernel Umount
    val kernelUmountStatus: String = "",
    val isKernelUmountEnabled: Boolean = false,

    // SELinux Hide
    val selinuxHideStatus: String = "",
    val isSelinuxHideEnabled: Boolean = false,

    // SU Log
    val sulogStatus: String = "",
    val isSulogEnabled: Boolean = false,

    // Umount Modules
    val isDefaultUmountModules: Boolean = false,

    // ADB Root
    val adbRootStatus: String = "",
    val isAdbRootEnabled: Boolean = false,

    // Shell SU ADB Only
    val shellSuAdbOnlyStatus: String = "",
    val isShellSuAdbOnlyEnabled: Boolean = false,

    val isLkmMode: Boolean = false,
    val isLateLoadMode: Boolean = false,

    // Auto Jailbreak
    val autoJailbreak: Boolean = false,

    // Soft Reboot
    val useSoftReboot: Boolean = false,

    // Built-in Zygisk: null until the first status read
    val zygiskStatus: ZygiskStatus? = null,
    val zygiskBackends: List<ZygiskBackend> = emptyList(),
    val zygiskBusy: Boolean = false,
    val showZygiskRebootDialog: Boolean = false,
    val showZnnRiskDialog: Boolean = false,
    val znnRiskSecondsRemaining: Int = 10,
    val znnRecoveryConfirmed: Boolean = false,
) {
    val zygiskEnabled: Boolean get() = zygiskStatus?.configured != null

    /** The backend shown as selected; turning the switch on picks [ZygiskBackend.Default]. */
    val zygiskSelected: ZygiskBackend
        get() = zygiskStatus?.configured
            ?: ZygiskBackend.Default.takeIf { it in zygiskBackends }
            ?: zygiskBackends.firstOrNull()
            ?: ZygiskBackend.Default

    val zygiskSwitchEnabled: Boolean
        get() = zygiskStatus != null && !zygiskBusy && !isLateLoadMode &&
            (zygiskEnabled || (zygiskStatus.conflictId == null && zygiskBackends.isNotEmpty()))

    val zygiskBackendEnabled: Boolean
        get() = zygiskEnabled && !zygiskBusy && !isLateLoadMode &&
            zygiskStatus?.conflictId == null && zygiskBackends.size > 1

    val zygiskNextNext: Boolean get() = zygiskStatus?.nextNextConfigured == true

    val canConfirmZnnRisk: Boolean
        get() = showZnnRiskDialog && znnRecoveryConfirmed && znnRiskSecondsRemaining == 0 &&
            zygiskNextNextEnabled && !zygiskNextNext

    val zygiskNextNextEnabled: Boolean
        get() = zygiskEnabled && !zygiskBusy && !isLateLoadMode &&
            zygiskStatus?.conflictId == null && zygiskStatus?.legacyVex != true
}

@Immutable
data class SettingsScreenActions(
    val onSetCheckUpdate: (Boolean) -> Unit,
    val onSetCheckModuleUpdate: (Boolean) -> Unit,
    val onOpenTheme: () -> Unit,
    val onSetUiModeIndex: (Int) -> Unit,
    val onOpenProfileTemplate: () -> Unit,
    val onSetSuCompatMode: (Int) -> Unit,
    val onSetKernelUmountEnabled: (Boolean) -> Unit,
    val onSetSelinuxHideEnabled: (Boolean) -> Unit,
    val onSetSulogEnabled: (Boolean) -> Unit,
    val onSetAdbRootEnabled: (Boolean) -> Unit,
    val onSetShellSuAdbOnlyEnabled: (Boolean) -> Unit,
    val onSetDefaultUmountModules: (Boolean) -> Unit,
    val onSetEnableWebDebugging: (Boolean) -> Unit,
    val onSetAutoJailbreak: (Boolean) -> Unit,
    val onSetUseSoftReboot: (Boolean) -> Unit,
    val onSetZygiskEnabled: (Boolean) -> Unit,
    val onSetZygiskBackend: (ZygiskBackend) -> Unit,
    val onSetZygiskNextNext: (Boolean) -> Unit,
    val onZygiskRebootLater: () -> Unit,
    val onZygiskRebootNow: () -> Unit,
    val onOpenAbout: () -> Unit,
    val onOpenSusfs: () -> Unit = {},
    val onOpenSui: () -> Unit = {},
    val onSetZnnRecoveryConfirmed: (Boolean) -> Unit = {},
    val onDismissZnnRisk: () -> Unit = {},
    val onConfirmZnnRisk: () -> Unit = {},
)
