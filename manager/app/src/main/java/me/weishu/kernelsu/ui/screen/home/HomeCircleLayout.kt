// Adapted from FolkSU (https://github.com/LyraVoid/FolkSU), GPL-3.0.
package me.weishu.kernelsu.ui.screen.home

import androidx.compose.runtime.Composable

/** CircleUI: the status row card over the two counters, then the system facts. */
@Composable
internal fun CircleHomeContent(
    state: HomeUiState,
    actions: HomeActions,
    superuserCount: Int,
    moduleEnabledCount: Int,
) {
    HomeStatusCard(state = state, actions = actions)
    // The counts only exist on a device where the kernel module is fully usable.
    if (state.isFullFeatured) {
        CountCardPair(
            superuserCount = superuserCount,
            moduleEnabledCount = moduleEnabledCount,
            onOpenSuperUser = actions.onOpenSuperUser,
            onOpenModule = actions.onOpenModule,
            layout = CountCardLayout.Horizontal,
        )
    }
    HomeInfoCard(systemInfo = state.systemInfo)
}
