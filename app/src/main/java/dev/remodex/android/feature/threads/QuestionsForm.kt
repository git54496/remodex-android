package dev.remodex.android.feature.threads

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.remodex.android.model.TimelineEntry

@Composable
internal fun QuestionsForm(entry: TimelineEntry) {
    val actions = LocalMessageInteractions.current
    val questions = remember(entry) { questionRows(entry) }
    val payload = remember(entry) { entry.payload() }
    val status = payload.optString("answerStatus")
    val answered = questions.isNotEmpty() && questions.all { it.answer != null }
    val locked = answered || status in setOf("sending", "uncertain")
    val signature = questions.joinToString { "${it.id}:${it.title}:${it.options}" }
    var answers by rememberSaveable(entry.id, signature) {
        mutableStateOf(ArrayList(questions.indices.map { payload.optJSONArray("draftAnswers")?.optString(it).orEmpty() }))
    }
    var confirmingRetry by remember { mutableStateOf(false) }
    if (questions.isEmpty()) Text(entry.body)
    questions.forEachIndexed { index, question ->
        Text(question.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        if (answered) Text(question.answer.orEmpty(), style = MaterialTheme.typography.bodyLarge)
        else if (locked) Text(answers.getOrNull(index).orEmpty().ifBlank { payload.optJSONArray("draftAnswers")?.optString(index).orEmpty() })
        else {
            question.options.forEach { option ->
                TextButton(enabled = actions.enabled, onClick = { answers = ArrayList(answers).apply { set(index, option) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("${if (answers[index] == option) "◉" else "○"}  $option", modifier = Modifier.fillMaxWidth())
                }
            }
            OutlinedTextField(value = answers[index], onValueChange = { value -> answers = ArrayList(answers).apply { set(index, value) } },
                enabled = actions.enabled, label = { Text(if (question.options.isEmpty()) "Your answer" else "Or write an answer") },
                modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 6)
        }
    }
    when {
        answered -> Text("✓  Answered", color = Color(0xFF27834B))
        status == "sending" -> Text("Sending answers…")
        status == "uncertain" -> {
            Text("Could not confirm delivery. Check the conversation before retrying.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { confirmingRetry = true }) { Text("Review answer") }
        }
        else -> {
            payload.optString("answerError").takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { actions.submitAnswer(entry.id, answers.toList()) },
                enabled = actions.enabled && questions.isNotEmpty() && answers.all { it.isNotBlank() }) {
                Text(if (questions.size == 1) "Send answer" else "Send answers")
            }
            if (!actions.enabled) Text("Reconnect to answer", style = MaterialTheme.typography.labelMedium)
        }
    }
    if (confirmingRetry) AlertDialog(onDismissRequest = { confirmingRetry = false },
        title = { Text("Review this answer?") }, text = { Text("It may already have arrived. Check the conversation first to avoid sending it twice.") },
        confirmButton = { TextButton(onClick = { confirmingRetry = false; actions.reviewAnswer(entry.id) }) { Text("Review answers") } },
        dismissButton = { TextButton(onClick = { confirmingRetry = false }) { Text("Cancel") } })
}
