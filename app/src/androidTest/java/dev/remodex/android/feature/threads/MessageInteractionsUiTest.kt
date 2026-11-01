package dev.remodex.android.feature.threads

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.remodex.android.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MessageInteractionsUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun chooseTypeAndSubmitAllAnswersOnce() {
        var sent: List<String>? = null
        var calls = 0
        compose.setContent {
            var entry by remember { mutableStateOf(TimelineEntry("q", "Codex", "Now", "", TimelineEntryKind.Questions,
                structuredPayload = """{"questions":[{"title":"Choose?","options":["A","B"]},{"title":"Reason?"}]}""")) }
            MaterialTheme { CompositionLocalProvider(LocalMessageInteractions provides MessageInteractions(enabled = true,
                submitAnswer = { id, answers ->
                    assertEquals("q", id); sent = answers; calls++
                    entry = withQuestionSubmission(entry, QuestionSubmission(answers, "sending", "id"))
                })) { SpecializedMessageCard(entry) } }
        }
        compose.onNodeWithText("Send answers").assertIsNotEnabled()
        compose.onNodeWithText("○  B").performClick()
        compose.onNodeWithText("Your answer").performTextInput("Because")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Send answers").assertIsEnabled().performClick()
        compose.onNodeWithText("Sending answers…").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("B", "Because"), sent); assertEquals(1, calls) }
    }
    @Test fun agentNameOpensExactThreadId() {
        var opened: String? = null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalMessageInteractions provides MessageInteractions(openAgent = { id, _ -> opened = id })) {
                SpecializedMessageCard(TimelineEntry("agent", "System", "Now", "", TimelineEntryKind.SubagentAction,
                    structuredPayload = """{"receiverThreadIds":["child-thread"],"agentNickname":"Ada","status":"completed"}"""))
            }
        } }
        compose.onNodeWithText("Ada  ·  completed").performClick()
        compose.runOnIdle { assertEquals("child-thread", opened) }
    }
    @Test fun pendingDispatchSurvivesRestartAsUncertain() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val key = "test-host/thread/question-${System.nanoTime()}"
        val store = QuestionSubmissionStore(context)
        store.write(key, QuestionSubmission(listOf("Yes"), "sending", "client"))
        assertEquals("uncertain", QuestionSubmissionStore(context).read().getValue(key).status)
        assertEquals(listOf("Yes"), QuestionSubmissionStore(context).read().getValue(key).answers)
        context.getSharedPreferences("question_submissions", android.content.Context.MODE_PRIVATE).edit().remove(key).commit()
    }
    @Test fun missingSidebarAgentBecomesSelectedConversation() {
        compose.setContent {}
        compose.runOnIdle {
            val app = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as android.app.Application
            val store = androidx.lifecycle.ViewModelStore()
            val vm = androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app))[dev.remodex.android.app.AppViewModel::class.java]
            val parent = vm.threadSummaries.first()
            vm.selectThread(parent.id)
            vm.openAgentThread("test-child-thread", "Ada")
            assertEquals("test-child-thread", vm.selectedThreadId)
            assertEquals(parent.id, vm.selectedThread?.parentThreadId)
            assertEquals("Ada", vm.selectedThread?.title)
            store.clear()
        }
    }
    @Test fun uncertainDeliveryNeedsExplicitReview() {
        var reviewed = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalMessageInteractions provides MessageInteractions(enabled = true, reviewAnswer = { reviewed = true })) {
                SpecializedMessageCard(withQuestionSubmission(TimelineEntry("q", "Codex", "Now", "", TimelineEntryKind.Questions,
                    structuredPayload = """{"questions":[{"title":"Ready?"}]}"""), QuestionSubmission(listOf("Yes"), "uncertain", "id")))
            }
        } }
        compose.onNodeWithText("Send answer").assertDoesNotExist()
        compose.onNodeWithText("Review answer").performClick()
        compose.onNodeWithText("Review answers").performClick()
        compose.runOnIdle { assertEquals(true, reviewed) }
    }
}
