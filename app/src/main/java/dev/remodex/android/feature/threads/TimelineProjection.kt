package dev.remodex.android.feature.threads

import dev.remodex.android.model.TimelineEntry
import dev.remodex.android.model.TimelineEntryKind

internal data class TimelineRow(
    val id: String,
    val entries: List<TimelineEntry>,
    val label: String? = null,
    val previousMessages: Boolean = false,
)

/** Collapse only completed, same-turn history. Interactive artifacts remain visible. */
internal fun projectTimeline(entries: List<TimelineEntry>, runningTurnId: String? = null): List<TimelineRow> {
    val hidden = mutableSetOf<String>()
    val groups = mutableMapOf<String, TimelineRow>()
    entries.filter { it.turnId != null }.groupBy { it.turnId }.forEach { (_, turn) ->
        val final = turn.lastOrNull { it.kind == TimelineEntryKind.Chat && (it.speaker.equals("Assistant", true) || it.speaker.equals("Codex", true)) && !it.isStreaming }
        if (final != null && final.turnCompleted && final.turnId != runningTurnId) {
            val before = turn.takeWhile { it.id != final.id }
            val lastUser = before.indexOfLast { it.speaker.equals("User", true) || it.speaker.equals("You", true) }
            val candidates = before.drop(lastUser + 1).filter {
                !it.isStreaming && it.attachments.isEmpty() && (it.kind in setOf(TimelineEntryKind.Thinking, TimelineEntryKind.ToolActivity, TimelineEntryKind.CommandExecution) ||
                    (it.kind == TimelineEntryKind.Chat && (it.speaker.equals("Assistant", true) || it.speaker.equals("Codex", true))))
            }
            if (candidates.isNotEmpty()) {
                hidden += candidates.map { it.id }
                groups[candidates.first().id] = TimelineRow("previous-${final.id}", candidates, "${candidates.size} previous messages", true)
            }
        }
    }
    val rows = mutableListOf<TimelineRow>()
    val tools = mutableListOf<TimelineEntry>()
    fun flush() {
        if (tools.isEmpty()) return
        val commands = tools.count { it.kind == TimelineEntryKind.CommandExecution }
        val calls = tools.size - commands
        val label = listOfNotNull(
            if (commands > 0) "Ran $commands command${if (commands == 1) "" else "s"}" else null,
            if (calls > 0) "$calls tool call${if (calls == 1) "" else "s"}" else null,
            if (tools.any { it.detail?.contains("failed", true) == true }) "failed" else null,
        ).joinToString(" · ")
        rows += TimelineRow("tools-${tools.first().id}", tools.toList(), label)
        tools.clear()
    }
    entries.forEach { entry ->
        groups[entry.id]?.let { flush(); rows += it }
        if (entry.id !in hidden) {
            if (!entry.isStreaming && entry.kind in setOf(TimelineEntryKind.CommandExecution, TimelineEntryKind.ToolActivity)) {
                if (tools.isNotEmpty() && tools.last().turnId != entry.turnId) flush()
                tools += entry
            } else {
                flush()
                rows += TimelineRow(entry.id, listOf(entry))
            }
        }
    }
    flush()
    return rows
}
