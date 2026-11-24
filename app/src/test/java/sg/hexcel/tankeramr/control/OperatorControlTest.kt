package sg.hexcel.tankeramr.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorControlTest {
    private var clock = 0L
    private fun channels(ch05: Int) = IntArray(16) { 1500 }.also { it[4] = ch05 }

    private fun telemetry(
        ch05: Ch05Position = Ch05Position.UP_RF_SIDEX,
        sidexAvailable: Boolean = true,
        sidexState: SidexState = SidexState.STOPPED,
        collisionMonitorActive: Boolean = false,
        collisionOverrideMode: String = "AUTO"
    ) = TelemetryPacket(
        speedMps = 0.0,
        rollDeg = 0.0,
        pitchDeg = 0.0,
        yawDeg = 0.0,
        alignmentErrorM = 0.0,
        headingErrorDeg = 0.0,
        source = "LIVOX_FASTLIO",
        ch05 = ch05,
        ch05Raw = when (ch05) {
            Ch05Position.UP_RF_SIDEX -> 1950
            Ch05Position.MIDDLE_SAFE -> 1500
            Ch05Position.DOWN_INTERNET -> 1050
            Ch05Position.UNKNOWN -> null
        },
        route = ConnectionRoute.LOCAL,
        internetArmed = false,
        sidexAvailable = sidexAvailable,
        sidexState = sidexState,
        sidexStage = if (sidexState == SidexState.STOPPED) "IDLE" else "ALIGN_STAIRS",
        sidexFaultCode = "",
        sidexLap = 0,
        obstaclePaused = sidexState == SidexState.PAUSED,
        sensorState = "READY",
        sidexArmed = sidexState == SidexState.RUNNING,
        queuedTurns = 0,
        totalTurns = 0,
        activeTurn = null,
        message = "Connected",
        collisionMonitorActive = collisionMonitorActive,
        collisionOverrideMode = collisionOverrideMode
    )

    @Test fun collisionMonitorStateComesFromJetsonTelemetry() {
        val control = OperatorControl { clock }
        control.updateRemote(telemetry(
            sidexState = SidexState.RUNNING,
            collisionMonitorActive = true,
            collisionOverrideMode = "FORCED_ON"
        ))
        assertTrue(control.snapshot().collisionMonitorActive)
        assertEquals("FORCED_ON", control.snapshot().collisionOverrideMode)

        control.updateRemote(telemetry(
            sidexState = SidexState.PAUSED,
            collisionMonitorActive = false,
            collisionOverrideMode = "FORCED_OFF"
        ))
        assertFalse(control.snapshot().collisionMonitorActive)
        assertEquals("FORCED_OFF", control.snapshot().collisionOverrideMode)
    }

    @Test fun ch05PositionsUseNewSidexAndInternetSemantics() {
        assertEquals(Ch05Position.UP_RF_SIDEX, OperatorControl.ch05From(1950))
        assertEquals(Ch05Position.MIDDLE_SAFE, OperatorControl.ch05From(1500))
        assertEquals(Ch05Position.DOWN_INTERNET, OperatorControl.ch05From(1050))
    }

    @Test fun ch05DisplayUsesCanonicalG30Values() {
        assertEquals(1050, OperatorControl.ch05DisplayValue(Ch05Position.DOWN_INTERNET))
        assertEquals(1500, OperatorControl.ch05DisplayValue(Ch05Position.MIDDLE_SAFE))
        assertEquals(1950, OperatorControl.ch05DisplayValue(Ch05Position.UP_RF_SIDEX))
        assertEquals(null, OperatorControl.ch05DisplayValue(Ch05Position.UNKNOWN))
    }

    @Test fun legacyJetsonWireNamesRemainCompatibleDuringPhaseA() {
        assertEquals(Ch05Position.UP_RF_SIDEX, OperatorControl.ch05FromWire("UP_MANUAL"))
        assertEquals(Ch05Position.DOWN_INTERNET, OperatorControl.ch05FromWire("DOWN_AUTONOMOUS"))
    }

    @Test fun sidexStartsOnlyWithCh05UpAndBackendReady() {
        val control = OperatorControl { clock }
        control.updateRemote(telemetry())
        assertEquals(SidexState.STARTING, control.toggleSidexDemo().sidexState)

        val down = OperatorControl { clock }
        down.updateRemote(telemetry(ch05 = Ch05Position.DOWN_INTERNET))
        assertEquals(SidexState.STOPPED, down.toggleSidexDemo().sidexState)
        assertEquals("SIDEX DEMO requires CH05 up", down.snapshot().message)

        val unavailable = OperatorControl { clock }
        unavailable.updateRemote(telemetry(sidexAvailable = false))
        assertEquals(SidexState.STOPPED, unavailable.toggleSidexDemo().sidexState)
        assertEquals("SIDEX backend is not ready", unavailable.snapshot().message)
    }

    @Test fun leavingCh05UpCancelsSidex() {
        val control = OperatorControl { clock }
        control.updateRemote(telemetry(sidexState = SidexState.RUNNING))
        assertTrue(control.snapshot().sidexArmed)
        control.updateChannels(channels(1500))
        assertEquals(SidexState.STOPPED, control.snapshot().sidexState)
        assertFalse(control.snapshot().sidexArmed)
    }

    @Test fun physicalAndUiTurnsAreLockedWhileSidexRuns() {
        val control = OperatorControl { clock }
        control.updateRemote(telemetry(sidexState = SidexState.RUNNING))
        assertEquals(0, control.enqueueTurn(TurnButton.R1_CENTER_CW).totalTurns)
        assertEquals("UI turn disabled by CH05/route/SIDEX state", control.snapshot().message)
    }

    @Test fun downIsTheOnlyInternetArmPosition() {
        val control = OperatorControl { clock }
        control.updateChannels(channels(1050))
        assertEquals(ConnectionRoute.INTERNET, control.snapshot().route)
        assertTrue(control.toggleInternetArm().internetArmed)
        control.updateChannels(channels(1950))
        assertEquals(ConnectionRoute.LOCAL, control.snapshot().route)
        assertFalse(control.snapshot().internetArmed)
    }

    @Test fun ch05AutomaticallySelectsLocalAndInternetDestinations() {
        val control = OperatorControl { clock }
        control.updateChannels(channels(1950))
        assertEquals(ConnectionRoute.LOCAL, control.snapshot().route)
        control.updateChannels(channels(1050))
        assertEquals(ConnectionRoute.INTERNET, control.snapshot().route)
    }

    @Test fun manualTurnQueueIsBoundedToFourFor360Degrees() {
        val control = OperatorControl { clock }
        control.updateChannels(channels(1950))
        repeat(4) { control.enqueueTurn(TurnButton.R1_CENTER_CW) }
        assertEquals(4, control.snapshot().queuedTurns + if (control.snapshot().activeTurn != null) 1 else 0)
        assertEquals("TURN LIMIT (4/4)", control.enqueueTurn(TurnButton.R2_RIGHT_CW).message)
    }

    @Test fun internetTurnRequiresCh05DownAndInternetArmed() {
        val control = OperatorControl { clock }
        control.updateChannels(channels(1050))
        assertEquals(0, control.enqueueTurn(TurnButton.R1_CENTER_CW).totalTurns)
        control.toggleInternetArm()
        assertEquals(1, control.enqueueTurn(TurnButton.R1_CENTER_CW).totalTurns)
    }

    @Test fun telemetryLossLocksControlsAndFaultsActiveSidex() {
        val control = OperatorControl { clock }
        control.updateRemote(telemetry(sidexState = SidexState.RUNNING))
        clock = 1600
        val stale = control.tick()
        assertEquals(null, stale.speedMetresPerSecond)
        assertEquals("STALE", stale.telemetrySource)
        assertEquals(Ch05Position.UNKNOWN, stale.ch05)
        assertEquals(SidexState.FAULT, stale.sidexState)
        assertFalse(stale.sidexArmed)
    }

    @Test fun yawDirectionUsesLeftForPositiveAndRightForNegative() {
        assertEquals("50.0° LEFT", ControlProtocol.direction(50.0, "RIGHT", "LEFT"))
        assertEquals("50.0° RIGHT", ControlProtocol.direction(-50.0, "RIGHT", "LEFT"))
        assertEquals("0.0°", ControlProtocol.direction(0.0, "RIGHT", "LEFT"))
    }

}
