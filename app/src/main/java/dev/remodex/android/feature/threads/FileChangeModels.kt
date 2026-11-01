package dev.remodex.android.feature.threads

import dev.remodex.android.model.TimelineEntry
import org.json.JSONArray
import org.json.JSONObject

internal data class DiffTotals(val additions: Int, val deletions: Int)
internal fun countDiffLines(diff: String): DiffTotals {
    var plus = 0; var minus = 0
    val hasHunks = diff.lineSequence().any { it.startsWith("@@") }
    var inHunk = !hasHunks
    diff.lineSequence().forEach { line ->
        when {
            line.startsWith("diff --git ") -> inHunk = !hasHunks
            line.startsWith("@@") -> inHunk = true
            inHunk && line.startsWith("+") && !line.startsWith("+++") -> plus++
            inHunk && line.startsWith("-") && !line.startsWith("---") -> minus++
        }
    }
    return DiffTotals(plus, minus)
}
internal data class FileChangeRow(val path: String, val action: String, val diff: String, val explicitTotals: DiffTotals? = null) {
    private val totals get() = explicitTotals ?: countDiffLines(diff)
    val additions get() = totals.additions
    val deletions get() = totals.deletions
}
private fun JSONObject.firstString(vararg keys: String): String = keys.firstNotNullOfOrNull { opt(it) as? String }?.trim().orEmpty()
private fun JSONObject.number(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
    when(val value = opt(key)) { is Number -> value.toInt(); is String -> value.trim().toIntOrNull(); else -> null }
}?.coerceAtLeast(0)
internal fun fileChangeRows(entry: TimelineEntry): List<FileChangeRow> {
    val payload = entry.payload()
    val rawChanges = payload.opt("changes")
    val objects = when(rawChanges) {
        is JSONArray -> (0 until rawChanges.length()).mapNotNull(rawChanges::optJSONObject)
        is JSONObject -> rawChanges.keys().asSequence().sorted().mapNotNull { key -> rawChanges.optJSONObject(key)?.let { JSONObject(it.toString()).apply { if (!has("path")) put("path", key) } } }.toList()
        else -> emptyList()
    }
    val changes = objects.mapNotNull { item ->
        val path = item.firstString("path", "file", "file_path", "filePath", "relative_path", "relativePath", "new_path", "newPath", "to", "target", "name", "old_path", "oldPath", "from")
        if (path.isBlank()) return@mapNotNull null
        val kind = item.firstString("kind", "action").ifBlank { item.optJSONObject("kind")?.optString("type").orEmpty() }.ifBlank { item.optString("type") }
        val action = when { kind.contains("add", true) || kind.contains("create", true) -> "Added"; kind.contains("delete", true) || kind.contains("remove", true) -> "Deleted"; else -> "Edited" }
        val plus = item.number("additions", "lines_added", "line_additions", "lineAdditions", "added", "insertions", "inserted", "num_added") ?: 0
        val minus = item.number("deletions", "lines_deleted", "line_deletions", "lineDeletions", "removed", "deleted", "num_deleted", "num_removed") ?: 0
        var diff = item.firstString("diff", "unified_diff", "unifiedDiff", "patch", "delta")
        if (diff.isBlank() && item.has("content") && action != "Edited") {
            val prefix = if (action == "Added") "+" else "-"
            diff = item.getString("content").lineSequence().joinToString("\n") { prefix + it }
        }
        FileChangeRow(path, action, diff, DiffTotals(plus, minus).takeIf { plus > 0 || minus > 0 })
    }
    if (changes.isNotEmpty()) return changes
    val raw = payload.optString("diff").ifBlank { entry.body }
    return raw.split(Regex("(?m)(?=^diff --git )")).mapNotNull { block ->
        val path = Regex("(?m)^\\+\\+\\+ b/(.+)$").find(block)?.groupValues?.get(1)
            ?: Regex("(?m)^--- a/(.+)$").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
        FileChangeRow(path, if (block.contains("--- /dev/null")) "Added" else if (block.contains("+++ /dev/null")) "Deleted" else "Edited", block.trim())
    }
}
internal fun sameChangedFile(lhs: String, rhs: String): Boolean {
    fun normalize(s: String) = s.trim().removePrefix("a/").removePrefix("b/").removePrefix("./").replace(Regex(":\\d+(?::\\d+)?$"), "").lowercase()
    val a = normalize(lhs); val b = normalize(rhs)
    return a == b || (a.startsWith('/') != b.startsWith('/') &&
        if(a.startsWith('/')) b.contains('/') && a.endsWith("/$b") else a.contains('/') && b.endsWith("/$a"))
}
/** iOS: explicit per-message totals override that message's patch counts; distinct messages add. */
internal fun summarizeFileChanges(entries: List<TimelineEntry>): List<FileChangeRow> {
    data class Aggregate(var path: String, var action: String, val diffs: MutableSet<String> = linkedSetOf(), var plus: Int = 0, var minus: Int = 0)
    val aggregates = mutableListOf<Aggregate>()
    entries.forEach { entry ->
        fileChangeRows(entry).forEach { file ->
            if (file.additions == 0 && file.deletions == 0) return@forEach
            val aggregate = aggregates.firstOrNull { sameChangedFile(it.path, file.path) }
                ?: Aggregate(file.path, file.action).also(aggregates::add)
            if (file.path.length < aggregate.path.length) aggregate.path = file.path
            val unique = file.diff.isNotBlank() && aggregate.diffs.add(file.diff.trim())
            if (file.explicitTotals != null || unique) {
                aggregate.plus += file.additions; aggregate.minus += file.deletions
            }
            if (file.action == "Added" || aggregate.action == "Added") aggregate.action = "Added"
            else if(file.action == "Deleted") aggregate.action = "Deleted"
        }
    }
    return aggregates.map { FileChangeRow(it.path, it.action, it.diffs.joinToString("\n\n"), DiffTotals(it.plus, it.minus)) }
}
