package com.shixiaoshi0417.lunexsu.data.zygisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZnnRiskConfirmationTest {
    private var now = 100_000L
    private val confirmation = ZnnRiskConfirmation { now }

    @Test
    fun cannotConfirmWithoutARequest() {
        confirmation.confirmRecovery(true)
        now += 60_000
        assertNull(confirmation.state())
        assertFalse(confirmation.consume())
    }

    @Test
    fun everyRequestStartsUncheckedAndAtTenSeconds() {
        confirmation.request()
        assertEquals(ZnnRiskConfirmation.State(10, false), confirmation.state())
        assertFalse(confirmation.consume())
    }

    @Test
    fun acknowledgementDoesNotSkipTheWait() {
        confirmation.request()
        confirmation.confirmRecovery(true)
        assertFalse(confirmation.state()!!.canContinue)
        assertFalse(confirmation.consume())
        now += 9_999
        assertEquals(1, confirmation.state()!!.secondsRemaining)
        assertFalse(confirmation.consume())
    }

    @Test
    fun elapsedTimeAloneDoesNotConfirmRecovery() {
        confirmation.request()
        now += 10_000
        assertEquals(0, confirmation.state()!!.secondsRemaining)
        assertFalse(confirmation.state()!!.canContinue)
        assertFalse(confirmation.consume())
    }

    @Test
    fun countdownRoundsUpAndCannotFinishOneMillisecondEarly() {
        confirmation.request()
        now += 1
        assertEquals(10, confirmation.state()!!.secondsRemaining)
        now += 999
        assertEquals(9, confirmation.state()!!.secondsRemaining)
        now += 8_999
        confirmation.confirmRecovery(true)
        assertEquals(1, confirmation.state()!!.secondsRemaining)
        assertFalse(confirmation.consume())
        now += 1
        assertTrue(confirmation.consume())
    }

    @Test
    fun confirmationIsSingleUseEvenForRepeatedClicks() {
        confirmation.request()
        confirmation.confirmRecovery(true)
        now += 10_000
        assertTrue(confirmation.consume())
        assertFalse(confirmation.consume())
        assertNull(confirmation.state())
    }

    @Test
    fun uncheckingRecoveryDisablesContinuationAgain() {
        confirmation.request()
        confirmation.confirmRecovery(true)
        now += 10_000
        assertTrue(confirmation.state()!!.canContinue)
        confirmation.confirmRecovery(false)
        assertFalse(confirmation.state()!!.canContinue)
        assertFalse(confirmation.consume())
    }

    @Test
    fun dismissingAfterTheWaitDoesNotLeaveApprovalBehind() {
        confirmation.request()
        confirmation.confirmRecovery(true)
        now += 10_000
        confirmation.dismiss()
        assertNull(confirmation.state())
        assertFalse(confirmation.consume())
        confirmation.request()
        assertEquals(ZnnRiskConfirmation.State(10, false), confirmation.state())
    }

    @Test
    fun reopeningAlwaysRestartsBothRequirements() {
        confirmation.request()
        confirmation.confirmRecovery(true)
        now += 10_000
        confirmation.request()
        assertEquals(ZnnRiskConfirmation.State(10, false), confirmation.state())
        now += 9_999
        assertFalse(confirmation.consume())
    }

    @Test
    fun aCancelledAttemptCannotApproveALaterAttempt() {
        confirmation.request()
        now += 5_000
        confirmation.confirmRecovery(true)
        confirmation.dismiss()
        now += 30_000
        confirmation.confirmRecovery(true)
        assertFalse(confirmation.consume())
        confirmation.request()
        assertEquals(ZnnRiskConfirmation.State(10, false), confirmation.state())
    }

    @Test
    fun timeMovingBackwardsCannotBypassTheWait() {
        confirmation.request()
        confirmation.confirmRecovery(true)
        now -= 10_000
        assertEquals(10, confirmation.state()!!.secondsRemaining)
        assertFalse(confirmation.consume())
    }

    @Test
    fun continuationNeverBecomesEnabledAfterDismissal() {
        confirmation.request()
        confirmation.confirmRecovery(true)
        confirmation.dismiss()
        now += 60_000
        assertNull(confirmation.state())
        assertFalse(confirmation.consume())
    }
}
