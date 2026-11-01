package dev.remodex.android.feature.threads

import dev.remodex.android.model.TimelineEntry
import dev.remodex.android.model.TimelineEntryKind
import org.json.JSONArray
import org.json.JSONObject

internal fun TimelineEntry.payload(): JSONObject = runCatching { JSONObject(structuredPayload.orEmpty()) }.getOrDefault(JSONObject())
private fun JSONArray?.objects() = (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }
internal data class FileChangeRow(val path: String, val action: String, val diff: String) {
    val additions get() = diff.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") }
    val deletions get() = diff.lineSequence().count { it.startsWith("-") && !it.startsWith("---") }
}
internal fun fileChangeRows(entry: TimelineEntry): List<FileChangeRow> {
    val changes = entry.payload().optJSONArray("changes").objects().mapNotNull {
        val path = it.optString("path").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val kind = it.optJSONObject("kind")?.optString("type") ?: it.optString("kind")
        FileChangeRow(path, when (kind.lowercase()) { "add", "create" -> "Added"; "delete", "remove" -> "Deleted"; else -> "Edited" }, it.optString("diff"))
    }
    if (changes.isNotEmpty()) return changes
    val raw = entry.payload().optString("diff").ifBlank { entry.body }
    return raw.split(Regex("(?m)(?=^diff --git )")).mapNotNull { block ->
        val path = Regex("(?m)^\\+\\+\\+ b/(.+)$").find(block)?.groupValues?.get(1)
            ?: Regex("(?m)^--- a/(.+)$").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
        FileChangeRow(path, if (block.contains("--- /dev/null")) "Added" else if (block.contains("+++ /dev/null")) "Deleted" else "Edited", block.trim())
    }
}
internal data class AgentRow(val id: String, val name: String, val status: String, val prompt: String, val model: String, val message: String)
private fun JSONObject.string(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
    opt(key)?.takeIf { it is String }?.toString()?.trim()?.takeIf(String::isNotEmpty)
}.orEmpty()
internal fun agentRows(entry: TimelineEntry): List<AgentRow> {
    val json = entry.payload()
    val statesValue = listOf("agentsStates", "statuses", "agents_states", "agentStates", "agent_states").firstNotNullOfOrNull { json.opt(it) }
    val states = statesValue as? JSONObject ?: JSONObject().apply {
        (statesValue as? JSONArray).objects().forEach { state -> state.string("threadId", "thread_id").takeIf(String::isNotBlank)?.let { put(it, state) } }
    }
    val refs = (json.optJSONArray("receiverAgents") ?: json.optJSONArray("receiver_agents") ?: json.optJSONArray("agents")).objects()
        .associateBy { it.string("threadId", "thread_id", "id") }
    val ids = linkedSetOf<String>()
    listOf("receiverThreadIds", "receiver_thread_ids", "threadIds", "thread_ids").forEach { key ->
        json.optJSONArray(key)?.let { array -> for (i in 0 until array.length()) array.optString(i).takeIf(String::isNotBlank)?.let(ids::add) }
    }
    json.string("receiverThreadId", "receiver_thread_id", "newThreadId", "new_thread_id", "agentId").takeIf(String::isNotBlank)?.let(ids::add)
    states.keys().forEach { ids += it }
    ids += refs.keys.filter(String::isNotBlank)
    return ids.map { id ->
        val state = states.optJSONObject(id) ?: JSONObject()
        val ref = refs[id] ?: JSONObject()
        AgentRow(id, ref.string("agentNickname", "nickname", "agent_nickname", "name")
            .ifBlank { state.string("agentNickname", "nickname") }.ifBlank { json.string("agentNickname", "agent_nickname", "new_agent_nickname") }.ifBlank { id.take(12) },
            state.string("status").ifBlank { (states.opt(id) as? String).orEmpty() }.ifBlank { json.string("status") }.ifBlank { "Unknown" },
            state.string("prompt").ifBlank { ref.string("prompt") }.ifBlank { json.string("prompt", "message") },
            ref.string("model").ifBlank { state.string("model") }.ifBlank { json.string("model") }, state.string("message", "text", "summary"))
    }
}
internal data class QuestionRow(val id: String, val title: String, val options: List<String>, val answer: String?)
internal fun questionRows(entry: TimelineEntry): List<QuestionRow> {
    val json = entry.payload()
    val answers = json.optJSONArray("resolvedAnswers")
    return json.optJSONArray("questions").objects().mapIndexed { index, q ->
        val options = q.optJSONArray("options")
        QuestionRow(q.optString("id").ifBlank { "$index" }, q.optString("title").ifBlank { q.optString("question") },
            (0 until (options?.length() ?: 0)).mapNotNull { i ->
                (options?.optJSONObject(i)?.optString("label") ?: options?.optString(i))?.takeIf(String::isNotBlank)
            }, answers?.optString(index)?.takeIf(String::isNotBlank))
    }.filter { it.title.isNotBlank() }
}
/** Async answers are ordinary user messages containing each question and answer, as on iOS. */
internal fun resolveQuestionAnswers(entries: List<TimelineEntry>): List<TimelineEntry> = entries.mapIndexed { index, entry ->
    if (entry.kind != TimelineEntryKind.Questions) return@mapIndexed entry
    val questions = questionRows(entry)
    if (questions.isEmpty()) return@mapIndexed entry
    val following = entries.drop(index + 1).takeWhile { it.kind != TimelineEntryKind.Questions }
    val response = following.firstOrNull { it.speaker.equals("You", true) && it.body.startsWith(questions.first().title + "\n") } ?: return@mapIndexed entry
    var remaining = response.body
    val answers = mutableListOf<String>()
    for ((i, question) in questions.withIndex()) {
        if (!remaining.startsWith(question.title + "\n")) return@mapIndexed entry
        remaining = remaining.removePrefix(question.title + "\n")
        val next = questions.getOrNull(i + 1)?.title
        val split = next?.let { remaining.indexOf("\n\n$it\n") } ?: remaining.length
        if (split < 0) return@mapIndexed entry
        answers += remaining.take(split).trim()
        remaining = remaining.drop(split).trimStart()
    }
    if (answers.any(String::isBlank)) entry else entry.copy(structuredPayload = entry.payload().put("resolvedAnswers", JSONArray(answers)).toString())
}
