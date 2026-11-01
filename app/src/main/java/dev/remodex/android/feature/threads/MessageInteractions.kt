package dev.remodex.android.feature.threads

import androidx.compose.runtime.staticCompositionLocalOf

internal data class MessageInteractions(
    val enabled: Boolean = false,
    val submitAnswer: (String, List<String>) -> Unit = { _, _ -> },
    val reviewAnswer: (String) -> Unit = {},
    val readImage: suspend (String) -> ByteArray = { error("Connect to your Mac to preview this image.") },
    val openAgent: (String, String) -> Unit = { _, _ -> },
)
internal val LocalMessageInteractions = staticCompositionLocalOf { MessageInteractions() }
