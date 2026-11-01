package dev.remodex.android.feature.threads

import dev.remodex.android.model.*
import org.junit.Assert.*
import org.junit.Test

class MessageCardModelsTest {
    private fun entry(kind: TimelineEntryKind, payload: String, body: String = "") = TimelineEntry("id", "System", "Now", body, kind, structuredPayload = payload)
    @Test fun fileChangesKeepPathsAndCountOnlyDiffContent() {
        val rows = fileChangeRows(entry(TimelineEntryKind.FileChange, """{"changes":[{"path":"src/App.kt","kind":{"type":"update"},"diff":"--- a/App.kt\n+++ b/App.kt\n-old\n+new\n+another"}]}"""))
        assertEquals("src/App.kt", rows.single().path)
        assertEquals(2, rows.single().additions)
        assertEquals(1, rows.single().deletions)
    }
    @Test fun rawDiffAndMalformedPayloadHaveSafeFallbacks() {
        assertEquals("a.kt", fileChangeRows(entry(TimelineEntryKind.FileChange, "bad", "--- a/a.kt\n+++ b/a.kt\n-old\n+new")).single().path)
        assertTrue(agentRows(entry(TimelineEntryKind.SubagentAction, "bad")).isEmpty())
    }
    @Test fun subagentsKeepPerAgentStatusAndTask() {
        val rows = agentRows(entry(TimelineEntryKind.SubagentAction, """{"receiverThreadIds":["a","b"],"prompt":"Inspect tests","agentsStates":{"a":{"agentNickname":"Ada","status":"completed","message":"Done"},"b":{"status":"running"}}}"""))
        assertEquals(listOf("completed", "running"), rows.map { it.status })
        assertEquals("Ada", rows.first().name)
        assertEquals("Inspect tests", rows.last().prompt)
    }
    @Test fun nativeRepliesBindByItemIdAndHideProtocolMarkup() {
        val q = entry(TimelineEntryKind.Questions, """{"questions":[{"title":"Ready?"}]}""")
        val raw = "<send_user_message_question_reply>\n" + """[{"questionItemId":"[\"request_user_input_async\",\"id\",0]","question":"Ready?","answer":"Yes"}]""" + "\n</send_user_message_question_reply>"
        val user = TimelineEntry("u", "You", "Now", raw)
        val rows = resolveQuestionAnswers(listOf(q, user))
        assertEquals("Yes", questionRows(rows.first()).single().answer)
        assertEquals("Ready?\nYes", rows.last().body)
        assertNull(questionRows(resolveQuestionAnswers(listOf(q.copy(id = "another"), user)).first()).single().answer)
    }

    @Test fun questionsResolveOnlyCanonicalAnswerAndRemainVisible() {
        val q = entry(TimelineEntryKind.Questions, """{"delivery":"async","questions":[{"title":"Which?","options":["A","B"]},{"title":"Why?","options":null}]}""")
        val user = TimelineEntry("u", "You", "Now", "Which?\nA\n\nWhy?\nUseful")
        val resolved = resolveQuestionAnswers(listOf(q, user)).first()
        assertEquals(listOf("A", "Useful"), questionRows(resolved).map { it.answer })
        assertNull(questionRows(resolveQuestionAnswers(listOf(q, user.copy(body = "Unrelated"))).first()).first().answer)
        val cached = ConversationSnapshot(emptyList(), mapOf("t" to ThreadDetail("t", "", "Ready", listOf(resolved))))
        assertEquals(resolved, decodeConversationSnapshot(encodeConversationSnapshot(cached)).details.getValue("t").entries.single())
    }
}
