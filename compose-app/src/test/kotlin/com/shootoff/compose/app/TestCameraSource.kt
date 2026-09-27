package com.shootoff.compose.app

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestCameraSource {
    @Test
    fun identicalNamesMatch() {
        assertTrue(sameCamera("HD Webcam C270", "HD Webcam C270"))
    }

    @Test
    fun namesThatDifferOnlyInTheirDevicePathMatch() {
        assertTrue(sameCamera("UVC Camera (046d:0825) /dev/video0", "UVC Camera (046d:0825) /dev/video2"))
    }

    @Test
    fun aDifferentCameraThatEndsUpOnTheSamePathDoesNotMatch() {
        assertFalse(sameCamera("UVC Camera (046d:0825) /dev/video0", "Other Camera (1234:5678) /dev/video0"))
    }

    @Test
    fun completelyDifferentNamesDoNotMatch() {
        assertFalse(sameCamera("HD Webcam C270", "Logitech BRIO"))
    }
}
