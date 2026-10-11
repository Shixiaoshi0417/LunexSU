package com.shixiaoshi0417.lunexsu.ui.component.material

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.shixiaoshi0417.lunexsu.ui.theme.pack.WallpaperSurfaceRole
import com.shixiaoshi0417.lunexsu.ui.theme.pack.resolveFolkWallpaperTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WallpaperNestingTest {
    private val tokens = resolveFolkWallpaperTokens(lightColorScheme(), 0.5f)

    @Test fun noMaterialOutsideWallpaperMode() {
        for (role in WallpaperSurfaceRole.entries) assertNull(wallpaperMaterialFor(null, null, role))
    }

    @Test fun aTopLevelContainerDrawsItsRole() {
        for (role in WallpaperSurfaceRole.entries) {
            assertEquals(tokens.material(role), wallpaperMaterialFor(tokens, null, role))
        }
    }

    @Test fun aNestedContainerDrawsNoSecondFill() {
        // A card inside a list group (or any material) would otherwise stack two translucent fills.
        for (outer in WallpaperSurfaceRole.entries) for (inner in WallpaperSurfaceRole.entries) {
            assertNull(wallpaperMaterialFor(tokens, outer, inner))
        }
    }

    @Test fun rowsInsideAGroupAreTransparentStandaloneRowsCarryTheGroup() {
        assertEquals(Color.Transparent, segmentedRowFill(tokens.group, insideMaterial = true))
        assertEquals(tokens.group.fill, segmentedRowFill(tokens.group, insideMaterial = false))
    }
}
