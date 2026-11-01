package dev.remodex.android.feature.threads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal data class ConnectionRecovery(val status: String, val summary: String, val progressing: Boolean)
internal fun connectionRecovery(hasSavedDevice: Boolean, connected: Boolean, reconnecting: Boolean, error: String?): ConnectionRecovery? {
    if (!hasSavedDevice || connected) return null
    return if (reconnecting) ConnectionRecovery("Reconnecting", "Trying to reconnect to your device.", true)
    else ConnectionRecovery("Interrupted", error?.trim()?.takeIf(String::isNotEmpty)
        ?: "Reconnect to your device to keep this chat in sync.", false)
}
@Composable
internal fun ConnectionRecoveryCard(snapshot: ConnectionRecovery, reconnect: () -> Unit) {
    val tint = if (snapshot.progressing) Color(0xFF50BEFF) else Color(0xFFFF9500)
    Surface(onClick = { if (!snapshot.progressing) reconnect() },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        color = Color.White.copy(alpha = 0.96f), shape = RoundedCornerShape(28.dp),
        border = BorderStroke(0.5.dp, Color.Black.copy(alpha = 0.16f)), shadowElevation = 2.dp) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = CircleShape, color = tint.copy(alpha = 0.1f)) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    Surface(Modifier.size(7.dp), shape = CircleShape, color = tint) {}
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Connection ·", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(snapshot.status, style = MaterialTheme.typography.labelSmall, color = tint)
                }
                Text(snapshot.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            }
            if (snapshot.progressing) CircularProgressIndicator(Modifier.size(18.dp), color = tint, strokeWidth = 2.dp)
            else Text("Reconnect", color = tint, style = MaterialTheme.typography.labelMedium)
        }
    }
}
