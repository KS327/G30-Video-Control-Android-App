package sg.hexcel.tankeramr.network

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraScannerTest {
    @Test fun productionDefaultsContainC12AndThreeVerifiedMainStreams() {
        val urls = CameraScanner.defaultCameraUrls()
        assertEquals(CameraScanner.C12_VISIBLE_URL, urls[0])
        assertEquals(listOf("100", "110", "120"),
            urls.slice(1..3).map { it.substringAfter("192.168.144.").substringBefore(':') })
        urls.slice(1..3).forEach { assertEquals(true, it.endsWith("stream=0.sdp")) }
    }

    @Test fun migrationUpgradesOnlyKnownXmSubstreamShape() {
        val old = "rtsp://admin:@192.168.144.120:554/user=admin&password=&channel=1&stream=1.sdp"
        assertEquals(old.replace("stream=1.sdp", "stream=0.sdp"), CameraScanner.preferMainStream(old))
        val custom = "rtsp://camera.example/live/stream=1.sdp"
        assertEquals(custom, CameraScanner.preferMainStream(custom))
    }
}
