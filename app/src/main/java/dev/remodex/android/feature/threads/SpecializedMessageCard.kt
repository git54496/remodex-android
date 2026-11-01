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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.remodex.android.model.TimelineEntry
import dev.remodex.android.model.TimelineEntryKind

@Composable
internal fun SpecializedMessageCard(entry: TimelineEntry) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(true) }
    var showAll by rememberSaveable(entry.id) { mutableStateOf(false) }
    var selectedDiff by remember(entry.id) { mutableStateOf<FileChangeRow?>(null) }
    val files = remember(entry) { fileChangeRows(entry) }
    val agents = remember(entry) { agentRows(entry) }
    val questionState = rememberSaveableStateHolder()
    val title = when (entry.kind) {
        TimelineEntryKind.FileChange -> if (files.isEmpty()) "File changes" else "${files.size} file${if (files.size == 1) "" else "s"} changed"
        TimelineEntryKind.SubagentAction -> entry.title ?: "Agents"
        else -> "Questions"
    }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${if (expanded) "⌄" else "›"}  $title", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp))
            if (expanded) when (entry.kind) {
                TimelineEntryKind.FileChange -> {
                    if (files.isEmpty()) Text(entry.body, style = MaterialTheme.typography.bodyMedium)
                    (if (showAll) files else files.take(if (entry.isStreaming) 4 else 3)).forEach { file ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = file.diff.isNotBlank()) { selectedDiff = file }) {
                            Text(file.action, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(file.path, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                            if (file.diff.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("+${file.additions}", color = Color(0xFF27834B), style = MaterialTheme.typography.labelMedium)
                                Text("−${file.deletions}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                                Text("View diff ›", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    if (files.size > (if (entry.isStreaming) 4 else 3)) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show fewer files" else "Show all ${files.size} files") }
                }
                TimelineEntryKind.SubagentAction -> {
                    if (agents.isEmpty()) Text(entry.body, style = MaterialTheme.typography.bodyMedium)
                    agents.forEach { agent -> AgentDetails(agent) }
                }
                TimelineEntryKind.Questions -> {
                    questionState.SaveableStateProvider(entry.id) { QuestionsForm(entry) }
                }
                else -> Unit
            }
        }
    }
    selectedDiff?.let { file ->
        Dialog(onDismissRequest = { selectedDiff = null }) {
            Surface(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp).heightIn(max = 600.dp)) {
                    Text(file.path, fontWeight = FontWeight.SemiBold)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                        file.diff.lineSequence().forEach { line ->
                            Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                color = when { line.startsWith("+") -> Color(0xFF27834B); line.startsWith("-") -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurface })
                        }
                    }
                    TextButton(onClick = { selectedDiff = null }) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun AgentDetails(agent: AgentRow) {
    val actions = LocalMessageInteractions.current
    var expanded by rememberSaveable(agent.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text("${agent.name}  ·  ${agent.status}", fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().clickable { actions.openAgent(agent.id, agent.name) }.padding(vertical = 8.dp))
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Details") }
        if (expanded) {
            if (agent.model.isNotBlank()) Text("Model: ${agent.model}", style = MaterialTheme.typography.labelMedium)
            if (agent.prompt.isNotBlank()) Text(agent.prompt, style = MaterialTheme.typography.bodyMedium)
            if (agent.message.isNotBlank()) Text(agent.message, style = MaterialTheme.typography.bodyMedium)
            Text(agent.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
