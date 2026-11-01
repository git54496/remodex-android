package dev.remodex.android.feature.threads

import dev.remodex.android.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IosFollowupTest {
    private fun change(payload: String) = TimelineEntry("f", "System", "Now", "", TimelineEntryKind.FileChange, structuredPayload = payload)
    @Test fun screenshotTurnUsesActualHistoryIncludingLaterCaptureFix() {
        val data = org.json.JSONArray(javaClass.getResource("/ios-file-change-history.json")!!.readText())
        val entries = (0 until data.length()).map { change(data.getJSONObject(it).toString()) }
        val beforeCaptureFix = summarizeFileChanges(entries.dropLast(1))
        assertEquals(49, beforeCaptureFix.sumOf { it.additions })
        assertEquals(3, beforeCaptureFix.sumOf { it.deletions })
        val current = summarizeFileChanges(entries)
        assertEquals(3, current.size)
        assertEquals(52, current.sumOf { it.additions })
        assertEquals(4, current.sumOf { it.deletions })
        assertFalse(current.any { it.path.endsWith("/CjkMarkdownFlavour.kt") })
    }
    @Test fun explicitTotalsOverridePatchCountsAndRawSourceIsExcluded() {
        val files = summarizeFileChanges(listOf(change("""{"changes":[
            {"path":"src/Body.kt","diff":"-old\n+new\n+other","additions":"1","deletions":1},
            {"path":"src/Raw.kt","diff":"package example\nclass Raw"},
            {"path":"src/Test.kt","additions":46,"deletions":0}
        ]}""")))
        assertEquals(listOf("src/Body.kt", "src/Test.kt"), files.map { it.path })
        assertEquals(47, files.sumOf { it.additions })
        assertEquals(1, files.sumOf { it.deletions })
    }
    @Test fun aliasesMergeWithoutMergingSameBasenamesInDifferentFolders() {
        assertTrue(sameChangedFile("/repo/src/A.kt", "./src/A.kt"))
        assertFalse(sameChangedFile("src/A.kt", "tests/A.kt"))
        assertFalse(sameChangedFile("/repo1/src/A.kt", "/repo2/src/A.kt"))
        val entries = listOf(change("""{"changes":{"src/A.kt":{"diff":"+one"}}}"""),
            change("""{"changes":[{"path":"/repo/src/A.kt","diff":"+two"}]}"""))
        assertEquals(2, summarizeFileChanges(entries).single().additions)
    }
    @Test fun creationContentAndExplicitZeroTotalsMatchIosFallback() {
        val rows = fileChangeRows(change("""{"changes":[{"path":"A","kind":"add","content":"one\ntwo"},{"path":"B","diff":"+new","additions":0,"deletions":0}]}"""))
        assertEquals(2, rows[0].additions)
        assertEquals(1, rows[1].additions)
    }
    @Test fun connectedHidesRecoveryAndRetryShowsProgress() {
        assertNull(connectionRecovery(true, true, false, "old error"))
        assertNull(connectionRecovery(false, false, false, null))
        assertEquals("Interrupted", connectionRecovery(true, false, false, null)?.status)
        assertEquals("Trying to reconnect to your device.", connectionRecovery(true, false, true, "old error")?.summary)
    }
    @Test fun voiceUsesIosWavFormatAndAppendsWithoutSending() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val bytes = voiceWav(pcm)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes.take(4).toByteArray()))
        assertEquals(24_000, buffer.getInt(24))
        assertEquals(1, buffer.getShort(22).toInt())
        assertEquals(16, buffer.getShort(34).toInt())
        assertEquals(4, buffer.getInt(40))
        assertArrayEquals(pcm, bytes.copyOfRange(44, bytes.size))
        assertEquals("draft\nhello", appendVoiceTranscript("draft ", " hello "))
        assertEquals("hello", appendVoiceTranscript("", "hello"))
    }
}
