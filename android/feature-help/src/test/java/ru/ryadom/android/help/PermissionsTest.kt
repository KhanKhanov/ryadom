package ru.ryadom.android.help

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Какие разрешения спрашиваются перед первым запросом помощи. */
class PermissionsTest {
    @Test
    fun cameraAndMicrophoneBeforeAndroid13() {
        assertEquals(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), permissionsToRequest(sdkInt = 32).toList())
    }

    @Test
    fun notificationsTooSinceAndroid13() {
        assertEquals(
            listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
            permissionsToRequest(sdkInt = 33).toList(),
        )
    }
}
