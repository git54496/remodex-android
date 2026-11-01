package dev.remodex.android.feature.threads

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

internal const val VoiceMaxSeconds = 150
internal data class VoiceClip(val bytes: ByteArray, val durationMs: Int)
internal fun voiceWav(pcm: ByteArray): ByteArray {
    require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= 24_000 * 2 * VoiceMaxSeconds)
    return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(24_000); putInt(48_000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(pcm.size); put(pcm)
    }.array()
}
internal fun appendVoiceTranscript(draft: String, transcript: String): String =
    if (draft.isBlank()) transcript.trim() else draft.trimEnd() + "\n" + transcript.trim()

internal class VoiceRecorder {
    @Volatile private var stopped = false
    fun stop() { stopped = true }
    fun prepare() { stopped = false }
    suspend fun record(onLevel: (Float, Int) -> Unit): VoiceClip = withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(24_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Microphone does not support 24 kHz recording." }
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 24_000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4800) * 2)
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone is unavailable." }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Could not start microphone." }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4800)
            val maxBytes = 48_000 * VoiceMaxSeconds
            while (!stopped && output.size() < maxBytes) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
                check(count > 0) { "Microphone recording was interrupted." }
                output.write(buffer, 0, count)
                var peak = 0
                for (i in 0 until count - 1 step 2) {
                    val sample = ((buffer[i].toInt() and 255) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()
                    peak = maxOf(peak, abs(sample))
                }
                val duration = output.size() / 48
                withContext(Dispatchers.Main) { onLevel(peak / 32768f, duration) }
            }
            val pcm = output.toByteArray()
            check(pcm.isNotEmpty()) { "Voice clips must include recorded audio." }
            VoiceClip(voiceWav(pcm), pcm.size / 48)
        } finally {
            runCatching { recorder.stop() }; recorder.release()
        }
    }
}

internal class VoiceUiState {
    var recording by mutableStateOf(false)
    var transcribing by mutableStateOf(false)
    var duration by mutableIntStateOf(0)
    var levels by mutableStateOf(emptyList<Float>())
    var error by mutableStateOf<String?>(null)
    var clip: VoiceClip? = null
    var job: Job? = null
    val recorder = VoiceRecorder()
    fun cancel() {
        recorder.stop(); job?.cancel(); clip = null
        recording = false; transcribing = false; levels = emptyList(); duration = 0; error = null
    }
}

@Composable
internal fun rememberVoiceInput(transcribe: suspend (VoiceClip) -> String, onTranscript: (String) -> Unit): Pair<VoiceUiState, () -> Unit> {
    val state = remember { VoiceUiState() }
    val scope = rememberCoroutineScope()
    val latestTranscribe by rememberUpdatedState(transcribe)
    val latestTranscript by rememberUpdatedState(onTranscript)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun begin() {
        if (state.job?.isCompleted == false) return
        state.error = null
        state.job = scope.launch {
            try {
                if (state.clip == null) {
                    state.recorder.prepare()
                    state.recording = true; state.duration = 0; state.levels = emptyList()
                    state.clip = state.recorder.record { level, duration ->
                        state.duration = duration; state.levels = (state.levels + level).takeLast(64)
                    }
                }
                state.recording = false; state.transcribing = true
                val text = latestTranscribe(requireNotNull(state.clip))
                ensureActive()
                latestTranscript(text); state.clip = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { state.error = error.message ?: "Voice transcription failed. Try again." }
            finally { state.recording = false; state.transcribing = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) begin() else state.error = "Allow microphone access in Android Settings to record voice."
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && state.recording) state.recorder.stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); state.cancel() }
    }
    val toggle: () -> Unit = {
        if (state.recording) state.recorder.stop()
        else if (!state.transcribing) {
            if (state.clip != null || ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    return state to toggle
}

@Composable
internal fun VoiceButton(state: VoiceUiState, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled && !state.transcribing, modifier = Modifier.size(44.dp)) {
        if (state.transcribing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(if (state.recording) Icons.Default.Stop else Icons.Outlined.MicNone,
            if (state.recording) "Transcribe voice" else "Record voice", tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun VoiceAccessory(state: VoiceUiState, retry: () -> Unit) {
    if (!state.recording && !state.transcribing && state.error == null) return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.96f), border = BorderStroke(0.5.dp, Color.Black.copy(alpha = 0.12f))) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.recording) {
                    Canvas(Modifier.weight(1f).height(18.dp)) {
                        val width = size.width / 64
                        state.levels.forEachIndexed { i, level ->
                            val height = (2.dp.toPx() + level * size.height).coerceAtMost(size.height)
                            drawLine(Color.Black, Offset(i * width, (size.height - height)/2), Offset(i * width, (size.height + height)/2), 2.dp.toPx())
                        }
                    }
                    Text("${state.duration / 60000}:${(state.duration / 1000 % 60).toString().padStart(2, '0')}")
                } else Text(if (state.transcribing) "Transcribing…" else "Voice · Action Needed", Modifier.weight(1f))
                IconButton(onClick = state::cancel) { Icon(Icons.Outlined.Close, "Cancel voice recording") }
            }
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall); TextButton(onClick = retry) { Text("Retry") } }
        }
    }
}
