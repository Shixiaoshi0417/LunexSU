package com.shixiaoshi0417.lunexsu.ui.component.choosekmidialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.CancellationException
import com.shixiaoshi0417.lunexsu.ui.util.getCurrentKmi
import com.shixiaoshi0417.lunexsu.ui.util.getSupportedKmis

data class KmiDialogState(
    val currentKmi: String = "",
    val supportedKmis: List<String> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
) {
    fun canConfirm(selection: String): Boolean =
        !loading && !failed && selection.isNotBlank() && selection in supportedKmis
}

@Composable
fun rememberKmiDialogState(show: Boolean): KmiDialogState {
    val state by produceState(KmiDialogState(), show) {
        if (!show) return@produceState
        value = KmiDialogState()
        val current = try {
            getCurrentKmi().trim()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ""
        }
        value = try {
            KmiDialogState(current, getSupportedKmis(), loading = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            KmiDialogState(current, loading = false, failed = true)
        }
    }
    return state
}
