package dev.remodex.android.feature.threads

import android.content.Context
import dev.remodex.android.model.TimelineEntry
import org.json.JSONArray
import org.json.JSONObject

internal data class QuestionSubmission(val answers: List<String>, val status: String, val clientId: String, val error: String = "")
internal class QuestionSubmissionStore(context: Context) {
    private val prefs = context.getSharedPreferences("question_submissions", Context.MODE_PRIVATE)
    fun read(): Map<String, QuestionSubmission> = prefs.all.mapNotNull { (key, raw) ->
        runCatching {
            val json = JSONObject(raw as String)
            val array = json.getJSONArray("answers")
            key to QuestionSubmission((0 until array.length()).map(array::getString),
                json.getString("status").let { if (it == "sending") "uncertain" else it }, json.getString("clientId"), json.optString("error"))
        }.getOrNull()
    }.toMap()
    fun write(key: String, value: QuestionSubmission) {
        // Commit before dispatch: a killed process must never turn an unconfirmed send into a fresh send.
        check(prefs.edit().putString(key, JSONObject().put("answers", JSONArray(value.answers)).put("status", value.status)
            .put("clientId", value.clientId).put("error", value.error).toString()).commit())
    }
}
internal fun questionResponseText(entry: TimelineEntry, answers: List<String>): String {
    val questions = questionRows(entry)
    require(questions.isNotEmpty() && questions.size == answers.size && answers.all { it.isNotBlank() })
    return questions.zip(answers).joinToString("\n\n") { (q, answer) -> "${q.title}\n${answer.trim()}" }
}
internal fun withQuestionSubmission(entry: TimelineEntry, submission: QuestionSubmission?): TimelineEntry {
    if (submission == null || questionRows(entry).all { it.answer != null }) return entry
    val payload = entry.payload().put("answerStatus", submission.status).put("answerError", submission.error)
        .put("draftAnswers", JSONArray(submission.answers))
    if (submission.status == "answered") payload.put("resolvedAnswers", JSONArray(submission.answers))
    return entry.copy(structuredPayload = payload.toString())
}
