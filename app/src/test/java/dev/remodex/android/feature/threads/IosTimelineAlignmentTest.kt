package dev.remodex.android.feature.threads

import dev.remodex.android.model.*
import org.junit.Assert.*
import org.junit.Test

class IosTimelineAlignmentTest {
    private fun entry(id: String, kind: TimelineEntryKind = TimelineEntryKind.Chat, body: String = id) =
        TimelineEntry(id, "Codex", "Now", body, kind, turnId = "t", turnCompleted = true)

    @Test fun imageArtifactsRemainVisibleAndChangesFollowFinal() {
        val image = entry("image", body = "![Generated image](</tmp/a.png>)")
        val files = listOf(entry("f1", TimelineEntryKind.FileChange), entry("f2", TimelineEntryKind.FileChange))
        val rows = projectTimeline(listOf(entry("progress"), image) + files + entry("final"))
        assertEquals(listOf("previous-final", "image", "final", "changes-final"), rows.map { it.id })
        assertEquals(files, rows.last().entries)
        assertTrue(rows.last().fileSummary)
        assertFalse(projectTimeline(files + entry("final"), "t").any { it.fileSummary })
    }

    @Test fun sameFileEditsAggregateCountsAndPreserveDiffs() {
        val first = entry("1", TimelineEntryKind.FileChange, "--- a/src/A.kt\n+++ b/src/A.kt\n-old\n+new")
        val second = entry("2", TimelineEntryKind.FileChange, "--- a/src/A.kt\n+++ b/src/A.kt\n+another")
        val summary = summarizeFileChanges(listOf(first, second)).single()
        assertEquals(2, summary.additions)
        assertEquals(1, summary.deletions)
        assertTrue(summary.diff.contains("+another"))
    }

    @Test fun imagePathsDecodeButCodeExamplesAndRemoteImagesStayMarkdown() {
        val parts = assistantContentParts("before\n![img](</tmp/a%20b.png>)\nafter")
        assertEquals(listOf(null, "/tmp/a b.png", null), parts.map { it.imagePath })
        assertNull(assistantContentParts("```markdown\n![img](/tmp/a.png)\n```").single().imagePath)
        assertNull(assistantContentParts("![img](https://example.com/a.png)").single().imagePath)
        assertEquals("/tmp/a+b.png", assistantContentParts("![img](/tmp/a+b.png)").single().imagePath)
    }
}
