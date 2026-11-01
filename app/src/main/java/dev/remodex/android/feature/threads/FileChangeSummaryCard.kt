package dev.remodex.android.feature.threads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.remodex.android.model.TimelineEntry

internal fun summarizeFileChanges(entries: List<TimelineEntry>): List<FileChangeRow> =
    entries.flatMap(::fileChangeRows).filter { it.diff.isNotBlank() }.groupBy { it.path }.map { (path, changes) ->
        FileChangeRow(path, changes.last().action, changes.map { it.diff.trim() }.distinct().joinToString("\n"))
    }

@Composable
internal fun FileChangeSummaryCard(entries: List<TimelineEntry>) {
    val files = remember(entries) { summarizeFileChanges(entries) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<List<FileChangeRow>?>(null) }
    Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("File changes", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Counts(files.sumOf { it.additions }, files.sumOf { it.deletions })
                Spacer(Modifier.weight(1f))
                Text("↗", Modifier.clickable { selected = files })
                Text(if (expanded) "⌄" else "›")
            }
            if (expanded) {
                if (files.isEmpty()) Text(entries.joinToString("\n") { it.body }, Modifier.padding(12.dp))
                (if (showAll) files else files.take(3)).forEach { file ->
                    HorizontalDivider(thickness = 0.5.dp)
                    Row(Modifier.fillMaxWidth().clickable { selected = listOf(file) }.padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(file.path.substringAfterLast('/'), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Counts(file.additions, file.deletions)
                    }
                }
                if (files.size > 3) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show less" else "Show more") }
            }
        }
    }
    selected?.let { changes -> Dialog(onDismissRequest = { selected = null }) {
        Surface(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp).heightIn(max = 600.dp)) {
                Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState())) {
                    changes.forEach { file ->
                        Text(file.path, style = MaterialTheme.typography.titleSmall)
                        file.diff.lineSequence().forEach { line -> Text(line, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall, color = when {
                                line.startsWith("+") -> Color(0xFF28C653)
                                line.startsWith("-") -> Color(0xFFFF3B30)
                                else -> MaterialTheme.colorScheme.onSurface
                            }) }
                    }
                }
                TextButton(onClick = { selected = null }) { Text("Close") }
            }
        }
    } }
}

@Composable
private fun Counts(additions: Int, deletions: Int) {
    if (additions > 0 || deletions > 0) {
        Text("+$additions", color = Color(0xFF28C653), style = MaterialTheme.typography.bodyMedium)
        Text("-$deletions", color = Color(0xFFFF3B30), style = MaterialTheme.typography.bodyMedium)
    }
}
