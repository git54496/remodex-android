package dev.remodex.android.feature.threads

import dev.remodex.android.model.TimelineEntry
import dev.remodex.android.model.TimelineEntryKind
import org.json.JSONArray
import org.json.JSONObject

internal fun TimelineEntry.payload(): JSONObject = runCatching { JSONObject(structuredPayload.orEmpty()) }.getOrDefault(JSONObject())
private fun JSONArray?.objects() = (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }
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
private fun resolveFormattedQuestionAnswers(entries: List<TimelineEntry>): List<TimelineEntry> = entries.mapIndexed { index, entry ->
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

private data class NativeQuestionReply(val itemId: String, val rows: List<Pair<String, String>>)
private fun nativeQuestionReply(text: String): NativeQuestionReply? = runCatching {
    val start = "<send_user_message_question_reply>\n"
    val end = "\n</send_user_message_question_reply>"
    require(text.startsWith(start) && text.endsWith(end))
    val rows = JSONArray(text.removePrefix(start).removeSuffix(end)).objects()
    require(rows.isNotEmpty())
    val decoded = rows.map { row ->
        val key = JSONArray(row.getString("questionItemId"))
        require(key.length() == 3 && key.getString(0) == "request_user_input_async")
        Triple(key.getString(1), key.getInt(2), row.getString("question") to row.getString("answer"))
    }.sortedBy { it.second }
    val id = decoded.first().first
    require(decoded.withIndex().all { (index, row) -> row.first == id && row.second == index && row.third.first.isNotBlank() && row.third.second.isNotBlank() })
    NativeQuestionReply(id, decoded.map { it.third })
}.getOrNull()

internal fun resolveQuestionAnswers(entries: List<TimelineEntry>): List<TimelineEntry> {
    val resolved = entries.toMutableList()
    entries.forEachIndexed { index, entry ->
        if (!entry.speaker.equals("You", true)) return@forEachIndexed
        val reply = nativeQuestionReply(entry.body) ?: return@forEachIndexed
        resolved[index] = entry.copy(body = reply.rows.joinToString("\n\n") { "${it.first}\n${it.second}" })
        val target = (index - 1 downTo 0).firstOrNull { resolved[it].id == reply.itemId }
            ?: (index - 1 downTo 0).firstOrNull { candidate ->
                // Older bridges flatten async items to assistant prose. Recover only
                // from a canonical native reply plus the matching question text.
                val original = resolved[candidate]
                original.kind == TimelineEntryKind.Chat && original.speaker == "Codex" &&
                    reply.rows.size == 1 && original.body.startsWith(reply.rows.first().first + "\n")
            }
        if (target != null && resolved[target].kind != TimelineEntryKind.Questions) {
            val original = resolved[target]
            val questions = JSONArray(reply.rows.map { JSONObject().put("title", it.first) })
            resolved[target] = original.copy(kind = TimelineEntryKind.Questions,
                structuredPayload = JSONObject().put("questions", questions).toString())
        }
        if (target != null && questionRows(resolved[target]).map { it.title } == reply.rows.map { it.first }) {
            resolved[target] = resolved[target].copy(structuredPayload = resolved[target].payload().put("resolvedAnswers", JSONArray(reply.rows.map { it.second })).toString())
        }
    }
    // Native replies must not be rebound by title to a different question item.
    val formatted = resolveFormattedQuestionAnswers(resolved.mapIndexed { index, entry ->
        if (nativeQuestionReply(entries[index].body) != null) entry.copy(speaker = "Native answer") else entry
    })
    return formatted.mapIndexed { index, entry -> entry.copy(speaker = entries[index].speaker) }
}
