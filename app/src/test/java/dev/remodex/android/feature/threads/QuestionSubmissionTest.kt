package dev.remodex.android.feature.threads

import dev.remodex.android.model.*
import org.junit.Assert.*
import org.junit.Test

class QuestionSubmissionTest {
    private val entry = TimelineEntry("q", "Codex", "Now", "", TimelineEntryKind.Questions,
        structuredPayload = """{"questions":[{"title":"Choose?","options":["A","B"]},{"title":"Reason?"}]}""")
    @Test fun answersRequireEveryQuestionAndPreserveOrder() {
        assertEquals("Choose?\nA\n\nReason?\nBecause", questionResponseText(entry, listOf(" A ", "Because")))
        assertTrue(runCatching { questionResponseText(entry, listOf("A", " ")) }.isFailure)
        assertTrue(runCatching { questionResponseText(entry, listOf("A")) }.isFailure)
    }
    @Test fun unconfirmedAnswersAreNeverPresentedAsAnswered() {
        val projected = withQuestionSubmission(entry, QuestionSubmission(listOf("A", "Because"), "uncertain", "client"))
        assertTrue(questionRows(projected).all { it.answer == null })
        assertEquals("uncertain", projected.payload().getString("answerStatus"))
        val confirmed = withQuestionSubmission(entry, QuestionSubmission(listOf("A", "Because"), "answered", "client"))
        assertEquals(listOf("A", "Because"), questionRows(confirmed).map { it.answer })
    }
}
