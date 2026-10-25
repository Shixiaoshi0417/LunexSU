package com.shixiaoshi0417.lunexsu.ui.screen.flash

import com.shixiaoshi0417.lunexsu.ui.util.FlashResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class FlashingStatus {
    FLASHING,
    SUCCESS,
    FAILED
}

internal data class FlashOperationState(
    val text: String = "",
    val showReboot: Boolean = false,
    val status: FlashingStatus = FlashingStatus.FLASHING,
)

/** One visit owns one execution, including the interval before the first output. */
internal class FlashOperation(private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(FlashOperationState())
    val state = mutableState.asStateFlow()
    private val log = StringBuilder()
    private var attempted = false

    @Synchronized
    fun logSnapshot(): String = log.toString()

    @Synchronized
    fun releaseFinishedLog() {
        if (mutableState.value.status == FlashingStatus.FLASHING) return
        log.setLength(0)
        mutableState.value = mutableState.value.copy(text = "")
    }

    @Synchronized
    private fun stdout(line: String) {
        log.appendLine(line)
        val text = if (line.startsWith("\u001b[H\u001b[J")) {
            line.substring(6) + "\n"
        } else {
            mutableState.value.text + line + "\n"
        }
        mutableState.value = mutableState.value.copy(text = text)
    }

    @Synchronized
    private fun stderr(line: String) {
        log.appendLine(line)
    }

    @Synchronized
    private fun finish(result: FlashResult) {
        var text = mutableState.value.text
        if (result.code != 0) {
            val error = "Error code: ${result.code}.\n ${result.err} Please save and check the log.\n"
            log.append(error)
            text += error
        }
        if (result.showReboot) text += "\n\n\n"
        mutableState.value = FlashOperationState(
            text = text,
            showReboot = result.showReboot,
            status = if (result.code == 0) FlashingStatus.SUCCESS else FlashingStatus.FAILED,
        )
    }

    @Synchronized
    fun start(
        restoredAttempt: Boolean,
        execute: suspend (stdout: (String) -> Unit, stderr: (String) -> Unit) -> FlashResult,
    ) {
        if (attempted) return
        attempted = true
        if (restoredAttempt) {
            unknownResult()
            return
        }
        scope.launch {
            try {
                finish(execute(::stdout, ::stderr))
            } catch (e: CancellationException) {
                unknownResult()
                throw e
            } catch (e: Exception) {
                stderr(e.toString())
                unknownResult()
            }
        }
    }

    private fun unknownResult() {
        finish(FlashResult(-1, "The previous operation's result is unknown. It will not be retried automatically.", false))
    }
}
