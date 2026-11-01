package dev.remodex.android.feature.threads

import android.content.Context
import android.util.AtomicFile
import dev.remodex.android.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class ConversationSnapshot(val summaries: List<ThreadSummary>, val details: Map<String, ThreadDetail>)

/** Private, atomic, per-host snapshots. A corrupt cache never prevents reconnect. */
internal class ConversationCacheStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "conversations-v1")
    private fun file(host: String): AtomicFile {
        val key = MessageDigest.getInstance("SHA-256").digest(host.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(root, "$key.json"))
    }
    fun read(host: String): ConversationSnapshot = runCatching {
        decodeConversationSnapshot(file(host).openRead().bufferedReader().use { it.readText() })
    }.getOrDefault(ConversationSnapshot(emptyList(), emptyMap()))
    fun write(host: String, snapshot: ConversationSnapshot) {
        root.mkdirs()
        val target = file(host)
        var stream: java.io.FileOutputStream? = null
        try {
            val bytes = encodeConversationSnapshot(snapshot).toByteArray()
            stream = target.startWrite()
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (_: Exception) { stream?.let(target::failWrite) }
    }
}

internal fun encodeConversationSnapshot(value: ConversationSnapshot): String = JSONObject()
    .put("version", 1)
    .put("summaries", JSONArray(value.summaries.map { encodeThreadSummary(it) }))
    .put("details", JSONArray(value.details.values.filterNot { it.entries.singleOrNull()?.id in setOf("${it.threadId}-loading", "${it.threadId}-failed") }.map { encodeThreadDetail(it) }))
    .toString()

internal fun decodeConversationSnapshot(raw: String): ConversationSnapshot {
    val json = JSONObject(raw)
    require(json.getInt("version") == 1)
    val summaries = json.getJSONArray("summaries").objects().map(::decodeThreadSummary)
    val details = json.getJSONArray("details").objects().map(::decodeThreadDetail).map {
        it.copy(status = if (it.status == ThreadStatus.Running) ThreadStatus.Waiting else it.status, activeTurnId = null, hasInterruptibleTurnWithoutId = false,
            entries = it.entries.map { entry -> entry.copy(isStreaming = false) })
    }.associateBy { it.threadId }
    return ConversationSnapshot(summaries.map { it.copy(status = if (it.status == ThreadStatus.Running) ThreadStatus.Waiting else it.status) }, details)
}
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun encodeImageAttachment(value: ImageAttachment): JSONObject = JSONObject()
    .put("id", value.id)
    .put("uri", value.uri)
    .put("thumbnailUri", value.thumbnailUri)
    .put("payloadDataUrl", value.payloadDataUrl)

private fun decodeImageAttachment(json: JSONObject): ImageAttachment = ImageAttachment(
    id = json.getString("id"),
    uri = json.getString("uri"),
    thumbnailUri = if (json.has("thumbnailUri") && !json.isNull("thumbnailUri")) json.getString("thumbnailUri") else null,
    payloadDataUrl = if (json.has("payloadDataUrl") && !json.isNull("payloadDataUrl")) json.getString("payloadDataUrl") else null,
)

private fun encodeTimelinePlanStep(value: TimelinePlanStep): JSONObject = JSONObject()
    .put("text", value.text)
    .put("status", value.status)

private fun decodeTimelinePlanStep(json: JSONObject): TimelinePlanStep = TimelinePlanStep(
    text = json.getString("text"),
    status = if (json.has("status") && !json.isNull("status")) json.getString("status") else null,
)

private fun encodeTimelineEntry(value: TimelineEntry): JSONObject = JSONObject()
    .put("id", value.id)
    .put("speaker", value.speaker)
    .put("timestampLabel", value.timestampLabel)
    .put("body", value.body)
    .put("kind", value.kind.name)
    .put("turnId", value.turnId)
    .put("turnCompleted", value.turnCompleted)
    .put("title", value.title)
    .put("detail", value.detail)
    .put("planSteps", JSONArray(value.planSteps.map { encodeTimelinePlanStep(it) }))
    .put("isStreaming", value.isStreaming)
    .put("attachments", JSONArray(value.attachments.map { encodeImageAttachment(it) }))

private fun decodeTimelineEntry(json: JSONObject): TimelineEntry = TimelineEntry(
    id = json.getString("id"),
    speaker = json.getString("speaker"),
    timestampLabel = json.getString("timestampLabel"),
    body = json.getString("body"),
    kind = TimelineEntryKind.valueOf(json.getString("kind")),
    turnId = if (json.has("turnId") && !json.isNull("turnId")) json.getString("turnId") else null,
    turnCompleted = json.optBoolean("turnCompleted"),
    title = if (json.has("title") && !json.isNull("title")) json.getString("title") else null,
    detail = if (json.has("detail") && !json.isNull("detail")) json.getString("detail") else null,
    planSteps = json.getJSONArray("planSteps").objects().map(::decodeTimelinePlanStep),
    isStreaming = json.optBoolean("isStreaming"),
    attachments = json.getJSONArray("attachments").objects().map(::decodeImageAttachment),
)

private fun encodeThreadDetail(value: ThreadDetail): JSONObject = JSONObject()
    .put("threadId", value.threadId)
    .put("subtitle", value.subtitle)
    .put("stateLabel", value.stateLabel)
    .put("entries", JSONArray(value.entries.map { encodeTimelineEntry(it) }))
    .put("status", value.status.name)
    .put("activeTurnId", value.activeTurnId)
    .put("latestTurnId", value.latestTurnId)
    .put("hasInterruptibleTurnWithoutId", value.hasInterruptibleTurnWithoutId)

private fun decodeThreadDetail(json: JSONObject): ThreadDetail = ThreadDetail(
    threadId = json.getString("threadId"),
    subtitle = json.getString("subtitle"),
    stateLabel = json.getString("stateLabel"),
    entries = json.getJSONArray("entries").objects().map(::decodeTimelineEntry),
    status = ThreadStatus.valueOf(json.getString("status")),
    activeTurnId = if (json.has("activeTurnId") && !json.isNull("activeTurnId")) json.getString("activeTurnId") else null,
    latestTurnId = if (json.has("latestTurnId") && !json.isNull("latestTurnId")) json.getString("latestTurnId") else null,
    hasInterruptibleTurnWithoutId = json.optBoolean("hasInterruptibleTurnWithoutId"),
)

private fun encodeThreadSummary(value: ThreadSummary): JSONObject = JSONObject()
    .put("id", value.id)
    .put("title", value.title)
    .put("projectPath", value.projectPath)
    .put("status", value.status.name)
    .put("preview", value.preview)
    .put("lastUpdatedLabel", value.lastUpdatedLabel)
    .put("model", value.model)
    .put("modelProvider", value.modelProvider)
    .put("parentThreadId", value.parentThreadId)
    .put("syncState", value.syncState.name)
    .put("desktopGroupId", value.desktopGroupId)
    .put("desktopGroupName", value.desktopGroupName)
    .put("desktopProjectRoot", value.desktopProjectRoot)

private fun decodeThreadSummary(json: JSONObject): ThreadSummary = ThreadSummary(
    id = json.getString("id"),
    title = json.getString("title"),
    projectPath = json.getString("projectPath"),
    status = ThreadStatus.valueOf(json.getString("status")),
    preview = json.getString("preview"),
    lastUpdatedLabel = json.getString("lastUpdatedLabel"),
    model = if (json.has("model") && !json.isNull("model")) json.getString("model") else null,
    modelProvider = if (json.has("modelProvider") && !json.isNull("modelProvider")) json.getString("modelProvider") else null,
    parentThreadId = if (json.has("parentThreadId") && !json.isNull("parentThreadId")) json.getString("parentThreadId") else null,
    syncState = ThreadSyncState.valueOf(json.getString("syncState")),
    desktopGroupId = if (json.has("desktopGroupId") && !json.isNull("desktopGroupId")) json.getString("desktopGroupId") else null,
    desktopGroupName = if (json.has("desktopGroupName") && !json.isNull("desktopGroupName")) json.getString("desktopGroupName") else null,
    desktopProjectRoot = if (json.has("desktopProjectRoot") && !json.isNull("desktopProjectRoot")) json.getString("desktopProjectRoot") else null,
)
