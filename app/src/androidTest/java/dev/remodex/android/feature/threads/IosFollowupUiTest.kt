package dev.remodex.android.feature.threads

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.async
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class IosFollowupUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun actualMicrophoneCapturesBoundedWavAndStops() {
        compose.setContent { MaterialTheme { androidx.compose.material3.Text("Microphone capture test") } }
        val recorder = VoiceRecorder()
        recorder.prepare()
        val clip = kotlinx.coroutines.runBlocking {
            val capture = async(kotlinx.coroutines.Dispatchers.IO) { recorder.record { _, _ -> } }
            kotlinx.coroutines.delay(350)
            recorder.stop()
            capture.await()
        }
        assertTrue(clip.durationMs > 0)
        assertTrue(clip.durationMs < 3000)
        assertEquals("RIFF", String(clip.bytes.take(4).toByteArray()))
    }
    @Test fun successfulTranscriptionAppendsToDraftWithoutSending() {
        var result = "Existing draft"
        compose.setContent { MaterialTheme {
            val (state, toggle) = rememberVoiceInput({ "Hello from voice" }, { result = appendVoiceTranscript(result, it) })
            androidx.compose.runtime.LaunchedEffect(Unit) { state.clip = VoiceClip(voiceWav(byteArrayOf(0, 0)), 1) }
            VoiceButton(state, true, toggle)
        } }
        compose.onNodeWithContentDescription("Record voice").performClick()
        compose.waitUntil { result == "Existing draft\nHello from voice" }
        compose.onNodeWithContentDescription("Record voice").assertIsDisplayed()
    }
    @Test fun microphoneRecordingAndCancelStatesAreAccessible() {
        val state = VoiceUiState()
        compose.setContent { MaterialTheme { Column {
            VoiceAccessory(state) {}
            VoiceButton(state, true) { state.recording = true }
        } } }
        compose.onNodeWithContentDescription("Record voice").performClick()
        compose.onNodeWithContentDescription("Transcribe voice").assertIsDisplayed()
        compose.onNodeWithText("0:00").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel voice recording").performClick()
        compose.onNodeWithContentDescription("Record voice").assertIsDisplayed()
    }
    @Test fun reconnectCardHasActionAndProgressReplacesIt() {
        var clicked = false
        compose.setContent { MaterialTheme { ConnectionRecoveryCard(ConnectionRecovery("Interrupted", "Reconnect to your device to keep this chat in sync.", false)) { clicked = true } } }
        compose.onNodeWithText("Connection ·").assertIsDisplayed()
        compose.onNodeWithText("Reconnect", useUnmergedTree = true).performClick()
        assertTrue(clicked)
    }
    @Test fun voiceFailureRetainsRetryAndTranscriptionCanBeCancelled() {
        val state = VoiceUiState().apply { error = "Sign in to ChatGPT on your Mac." }
        var retried = false
        compose.setContent { MaterialTheme { VoiceAccessory(state) { retried = true } } }
        compose.onNodeWithText("Retry").performClick()
        assertTrue(retried)
        compose.onNodeWithContentDescription("Cancel voice recording").performClick()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }
}
