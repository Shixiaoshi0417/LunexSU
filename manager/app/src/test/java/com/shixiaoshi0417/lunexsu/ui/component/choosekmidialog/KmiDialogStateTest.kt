package com.shixiaoshi0417.lunexsu.ui.component.choosekmidialog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KmiDialogStateTest {
    private val kmi = "android12-5.10"

    @Test fun detectedDeviceDoesNotInventABundledModule() {
        assertFalse(KmiDialogState(kmi, loading = false).canConfirm(kmi))
    }

    @Test fun loadingAndFailedReadsNeverAcceptAStaleSelection() {
        assertFalse(KmiDialogState(kmi, listOf(kmi)).canConfirm(kmi))
        assertFalse(KmiDialogState(kmi, listOf(kmi), loading = false, failed = true).canConfirm(kmi))
    }

    @Test fun confirmationRequiresAnExplicitAvailableModule() {
        val state = KmiDialogState(kmi, listOf(kmi, "android13-5.10"), loading = false)
        assertTrue(state.canConfirm(kmi))
        assertTrue(state.canConfirm("android13-5.10"))
        assertFalse(state.canConfirm(""))
        assertFalse(state.canConfirm("android14-6.1"))
    }
}
