package dev.remodex.android.feature.threads

import dev.remodex.android.model.*
import org.junit.Assert.*
import org.junit.Test

class ConversationCacheTest {
    private fun entry(id: String, kind: TimelineEntryKind = TimelineEntryKind.Chat, speaker: String = "Codex", turn: String = "t", completed: Boolean = true) =
        TimelineEntry(id, speaker, "Now", "body $id", kind, turnId = turn, turnCompleted = completed)

    @Test fun snapshotRestoresContentsButNeverStaleRunningState() {
        val message = entry("a").copy(isStreaming = true, attachments = listOf(ImageAttachment("image", "file:///image", "data:image/jpeg;base64,abc")))
        val detail = ThreadDetail("t", "project", "Ready", listOf(message), ThreadStatus.Running, "active")
        val snapshot = ConversationSnapshot(listOf(ThreadSummary("t", "Title", "/project", ThreadStatus.Running, "preview", "Now")), mapOf("t" to detail))
        val restored = decodeConversationSnapshot(encodeConversationSnapshot(snapshot))
        assertEquals(message.copy(isStreaming = false), restored.details.getValue("t").entries.single())
        assertFalse(restored.details.getValue("t").isRunning)
        assertEquals("Title", restored.summaries.single().title)
    }

    @Test fun completedTurnKeepsUserFinalAndArtifactsVisible() {
        val rows = projectTimeline(listOf(entry("u", speaker = "You"), entry("progress"), entry("tool", TimelineEntryKind.ToolActivity), entry("file", TimelineEntryKind.FileChange), entry("final")))
        assertEquals(listOf("u", "previous-final", "file", "final"), rows.map { it.id })
        assertEquals(listOf("progress", "tool"), rows[1].entries.map { it.id })
    }

    @Test fun liveTurnGroupsToolsWithoutHidingProgressOrCrossingTurns() {
        val rows = projectTimeline(listOf(entry("progress", completed = false), entry("cmd", TimelineEntryKind.CommandExecution, completed = false), entry("tool", TimelineEntryKind.ToolActivity, completed = false), entry("next", TimelineEntryKind.ToolActivity, turn = "other", completed = false)))
        assertEquals(3, rows.size)
        assertEquals("Ran 1 command · 1 tool call", rows[1].label)
        assertFalse(rows.any { it.previousMessages })
    }

    @Test fun streamingToolsAndAttachmentsStayVisible() {
        val image = entry("image").copy(attachments = listOf(ImageAttachment("i", "uri")))
        val rows = projectTimeline(listOf(entry("tool", TimelineEntryKind.ToolActivity).copy(isStreaming = true), image, entry("final")))
        assertTrue(rows.all { it.label == null })
    }
}
