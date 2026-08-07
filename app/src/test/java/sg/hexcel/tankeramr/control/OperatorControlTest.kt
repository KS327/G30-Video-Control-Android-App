package sg.hexcel.tankeramr.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorControlTest {
    private var clock = 0L
    private fun channels(ch05: Int) = IntArray(16) { 1500 }.also { it[4] = ch05 }

    @Test fun bootWithCh05UpEnablesLocalManualImmediately() {
        val control = OperatorControl { clock }
        assertEquals(Ch05Position.UP_MANUAL, control.updateChannels(channels(1950)).ch05)
        assertEquals(1, control.enqueueTurn(TurnButton.L1_CENTER_ACW).queuedTurns + 1)
    }

    @Test fun middleDisarmsAndRestoresLocalRoute() {
        val control = OperatorControl { clock }
        control.updateChannels(channels(1950))
        clock = 600
        control.selectRoute(ConnectionRoute.INTERNET)
        clock = 1200
        control.tick()
        control.toggleInternetArm()
        assertTrue(control.snapshot().internetArmed)
        control.updateChannels(channels(1500))
        clock = 1800
        val state = control.updateChannels(channels(1500))
        assertFalse(state.internetArmed)
        assertEquals(ConnectionRoute.LOCAL, state.route)
    }

    @Test fun autonomousMustRearmAfterLeavingDownButProcessStaysReady() {
        val control = OperatorControl { clock }
        control.updateChannels(channels(1500))
        control.toggleAutonomousProcess()
        clock = 3000
        control.tick()
        control.updateChannels(channels(1050))
        assertTrue(control.toggleAutonomousArm().autonomousArmed)
        control.updateChannels(channels(1500))
        assertFalse(control.snapshot().autonomousArmed)
        assertEquals(AutonomousState.READY, control.snapshot().autonomousState)
        control.updateChannels(channels(1050))
        assertFalse(control.snapshot().autonomousArmed)
    }

    @Test fun turnQueueNeverExceedsSixIncludingActive() {
        val control = OperatorControl { clock }
        control.updateChannels(channels(1950))
        repeat(6) { control.enqueueTurn(TurnButton.R1_CENTER_CW) }
        assertEquals(6, control.snapshot().queuedTurns + if (control.snapshot().activeTurn != null) 1 else 0)
        assertEquals("QUEUE FULL (6/6)", control.enqueueTurn(TurnButton.R2_RIGHT_CW).message)
    }

    @Test fun telemetryBecomesStaleAfterTimeout() {
        val control = OperatorControl { clock }
        control.updateRemote(TelemetryPacket(
            speedMps = 1.25,
            rollDeg = -12.0,
            pitchDeg = 7.0,
            source = "LIVOX_FASTLIO",
            ch05 = Ch05Position.MIDDLE_SAFE,
            ch05Raw = 1500,
            route = ConnectionRoute.LOCAL,
            internetArmed = false,
            autonomousState = AutonomousState.STOPPED,
            autonomousArmed = false,
            queuedTurns = 0,
            totalTurns = 0,
            activeTurn = null,
            message = "Connected"
        ))
        assertEquals(1.25, control.snapshot().speedMetresPerSecond!!, 0.0)
        clock = 1600
        val stale = control.tick()
        assertEquals(null, stale.speedMetresPerSecond)
        assertEquals("STALE", stale.telemetrySource)
    }
}
