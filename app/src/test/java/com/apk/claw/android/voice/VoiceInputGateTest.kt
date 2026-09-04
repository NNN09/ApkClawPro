package com.apk.claw.android.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInputGateTest {

    @Test
    fun decide_startsOfflineWhenModelInstalledAndMicGranted() {
        assertEquals(
            VoiceInputAction.START_OFFLINE,
            VoiceInputGate.decide(hasRecordAudio = true, offlineModelAvailable = true)
        )
    }

    @Test
    fun decide_requestsPermissionWhenModelInstalledButMicMissing() {
        assertEquals(
            VoiceInputAction.REQUEST_PERMISSION,
            VoiceInputGate.decide(hasRecordAudio = false, offlineModelAvailable = true)
        )
    }

    @Test
    fun decide_goesToDownloadWhenModelMissing() {
        assertEquals(
            VoiceInputAction.GO_MODEL_DOWNLOAD,
            VoiceInputGate.decide(hasRecordAudio = true, offlineModelAvailable = false)
        )
    }

    @Test
    fun decide_modelMissingTakesPrecedenceOverPermission() {
        assertEquals(
            VoiceInputAction.GO_MODEL_DOWNLOAD,
            VoiceInputGate.decide(hasRecordAudio = false, offlineModelAvailable = false)
        )
    }
}
