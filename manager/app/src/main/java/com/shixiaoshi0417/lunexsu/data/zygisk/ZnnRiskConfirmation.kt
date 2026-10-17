package com.shixiaoshi0417.lunexsu.data.zygisk

/** A fresh, single-use acknowledgement for each enable attempt; never persisted. */
internal class ZnnRiskConfirmation(private val elapsedRealtime: () -> Long) {
    private var startedAt: Long? = null
    private var recoveryConfirmed = false

    data class State(val secondsRemaining: Int, val recoveryConfirmed: Boolean) {
        val canContinue: Boolean get() = secondsRemaining == 0 && recoveryConfirmed
    }

    fun request() {
        startedAt = elapsedRealtime()
        recoveryConfirmed = false
    }

    fun state(): State? {
        val start = startedAt ?: return null
        val elapsed = (elapsedRealtime() - start).coerceAtLeast(0L)
        val remaining = (WAIT_MILLIS - elapsed).coerceAtLeast(0L)
        return State(((remaining + 999L) / 1000L).toInt(), recoveryConfirmed)
    }

    fun confirmRecovery(confirmed: Boolean) {
        if (startedAt != null) recoveryConfirmed = confirmed
    }

    fun consume(): Boolean {
        if (state()?.canContinue != true) return false
        dismiss()
        return true
    }

    fun dismiss() {
        startedAt = null
        recoveryConfirmed = false
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}
