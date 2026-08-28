package sg.hexcel.tankeramr.gimbal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class C12GimbalControllerTest {
    @Test fun centreAndDeadbandProduceStop() {
        assertEquals(0f, C12GimbalController.channelToRate(1500))
        assertEquals(0f, C12GimbalController.channelToRate(1565))
        assertEquals(0f, C12GimbalController.channelToRate(1435))
    }

    @Test fun extremesProduceProportionalSignedRate() {
        assertEquals(40f, C12GimbalController.channelToRate(1950))
        assertEquals(-40f, C12GimbalController.channelToRate(1050))
        assertTrue(C12GimbalController.channelToRate(1750) in 0.1f..39.9f)
        assertTrue(C12GimbalController.channelToRate(1250) in -39.9f..-0.1f)
    }
}
