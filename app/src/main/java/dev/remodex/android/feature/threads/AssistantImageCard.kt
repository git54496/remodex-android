package dev.remodex.android.feature.threads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import dev.remodex.android.ui.theme.Ink
import kotlinx.coroutines.CancellationException

internal data class AssistantContentPart(val text: String, val imagePath: String? = null)

/** Standalone local images are workspace artifacts; fenced examples stay literal Markdown. */
internal fun assistantContentParts(text: String): List<AssistantContentPart> {
    val parts = mutableListOf<AssistantContentPart>()
    val prose = StringBuilder()
    var fence: String? = null
    fun flush() { if (prose.isNotBlank()) parts += AssistantContentPart(prose.toString().trim()); prose.clear() }
    text.lineSequence().forEach { line ->
        val trimmed = line.trim()
        val marker = Regex("^(`{3,}|~{3,})").find(trimmed)?.value
        if (marker != null) {
            if (fence == null) fence = marker else if (marker.first() == fence!!.first() && marker.length >= fence!!.length) fence = null
            prose.appendLine(line)
        } else {
            val match = if (fence == null) Regex("^!\\[[^]]*]\\((?:<([^>]+)>|([^\\s]+))\\)$").matchEntire(trimmed) else null
            val raw = match?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            val path = raw?.let { runCatching { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
            if (path != null && (path.startsWith("/") || path.startsWith("./") || path.startsWith("../"))) {
                flush(); parts += AssistantContentPart(line, path)
            } else prose.appendLine(line)
        }
    }
    flush()
    return parts
}

@Composable
internal fun AssistantMarkdownBody(text: String) {
    val parts = remember(text) { assistantContentParts(text) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        parts.forEach { part ->
            if (part.imagePath != null) WorkspaceImageCard(part.imagePath)
            else MarkdownBody(part.text, Ink, MaterialTheme.typography.bodyLarge, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun WorkspaceImageCard(path: String) {
    val actions = LocalMessageInteractions.current
    var open by remember(path) { mutableStateOf(false) }
    val blue = Color(0xFF0095FF)
    Surface(onClick = { open = true }, shape = RoundedCornerShape(18.dp),
        color = Color(0xFFF2F2F7), border = BorderStroke(0.5.dp, Color.Black.copy(alpha = 0.06f))) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = blue.copy(alpha = 0.12f)) {
                Icon(Icons.Outlined.Image, "Image", tint = blue, modifier = Modifier.padding(9.dp).size(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(path.substringAfterLast('/'), fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.OpenInFull, "Preview image", tint = Color.Gray, modifier = Modifier.size(16.dp))
        }
    }
    if (open) Dialog(onDismissRequest = { open = false }) {
        var data by remember(path) { mutableStateOf<ByteArray?>(null) }
        var error by remember(path) { mutableStateOf<String?>(null) }
        var attempt by remember { mutableIntStateOf(0) }
        LaunchedEffect(path, attempt) {
            error = null
            try { data = actions.readImage(path) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not load image." }
        }
        Surface(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(path.substringAfterLast('/'), fontWeight = FontWeight.SemiBold)
                if (data != null) AsyncImage(data, path, Modifier.fillMaxWidth().heightIn(max = 500.dp))
                else if (error == null) CircularProgressIndicator()
                error?.let { Text(it); TextButton(onClick = { attempt++ }) { Text("Retry") } }
                TextButton(onClick = { open = false }) { Text("Close") }
            }
        }
    }
}
