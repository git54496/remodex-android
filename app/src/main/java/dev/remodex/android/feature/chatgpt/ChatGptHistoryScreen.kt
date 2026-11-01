package dev.remodex.android.feature.chatgpt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.remodex.android.feature.threads.MarkdownBody
import dev.remodex.android.ui.theme.Ink
import dev.remodex.android.ui.theme.InkSecondary
import dev.remodex.android.ui.theme.LightBg
import kotlinx.coroutines.CancellationException

@Composable
fun ChatGptHistoryScreen(
    connected: Boolean,
    loadPage: suspend (Int) -> ChatGptPage,
    loadDetail: suspend (String) -> ChatGptDetail,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chats by remember { mutableStateOf(emptyList<ChatGptChat>()) }
    var nextOffset by remember { mutableStateOf<Int?>(null) }
    var requestedOffset by remember { mutableStateOf(0) }
    var pageAttempt by remember { mutableStateOf(0) }
    var pageLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<ChatGptChat?>(null) }
    var detail by remember { mutableStateOf<ChatGptDetail?>(null) }
    var detailAttempt by remember { mutableStateOf(0) }
    var detailError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(connected, pageAttempt) {
        if (!connected) return@LaunchedEffect
        pageLoading = true
        pageError = null
        try {
            val page = loadPage(requestedOffset)
            chats = (if (requestedOffset == 0) page.items else chats + page.items).distinctBy { it.id }
            nextOffset = page.nextOffset
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { pageError = e.message ?: "Could not load ChatGPT history." }
        finally { pageLoading = false }
    }
    LaunchedEffect(selected?.id, detailAttempt) {
        detail = null
        detailError = null
        val id = selected?.id ?: return@LaunchedEffect
        try { detail = loadDetail(id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { detailError = e.message ?: "Could not load this conversation." }
    }
    val back = { if (selected != null) selected = null else onBack() }
    BackHandler(onBack = back)

    Surface(modifier = modifier.fillMaxSize(), color = LightBg) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = back) { Text(if (selected == null) "Back" else "History") }
                if (connected && selected == null) TextButton(
                    enabled = !pageLoading,
                    onClick = { requestedOffset = 0; pageAttempt++ },
                ) { Text("Refresh") }
            }
            Text(selected?.title ?: "ChatGPT history", style = MaterialTheme.typography.titleLarge,
                color = Ink, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (!connected) {
                Text("Connect to your Mac to view ChatGPT history.", color = InkSecondary, modifier = Modifier.padding(20.dp))
            } else if (selected == null) {
                LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(20.dp)) {
                    items(chats, key = { it.id }) { chat ->
                        Column(Modifier.fillMaxWidth().clickable { detail = null; detailError = null; selected = chat }.padding(vertical = 16.dp)) {
                            Text(chat.title, color = Ink, style = MaterialTheme.typography.titleMedium)
                            chat.updatedAt?.let { date ->
                                Text(date.substringBefore('T'), color = InkSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider()
                    }
                    item {
                        if (pageLoading) CircularProgressIndicator(Modifier.padding(16.dp))
                        else if (pageError != null) {
                            Text(pageError!!, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { pageAttempt++ }) { Text("Retry") }
                        } else if (chats.isEmpty() && nextOffset == null) {
                            Text("No ChatGPT conversations yet.", color = InkSecondary)
                        }
                        if (!pageLoading && pageError == null && nextOffset != null) {
                            TextButton(onClick = { requestedOffset = nextOffset!!; pageAttempt++ }) { Text("Load more") }
                        }
                    }
                }
            } else {
                key(selected!!.id) {
                    LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        item { Text("Read-only · Text view", color = InkSecondary, style = MaterialTheme.typography.labelMedium) }
                        val current = detail
                        if (current != null) {
                            if (current.messages.isEmpty()) item { Text("No displayable messages in this conversation.", color = InkSecondary) }
                            items(current.messages) { message ->
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(if (message.role == "user") "You" else "ChatGPT", color = InkSecondary,
                                        style = MaterialTheme.typography.labelLarge)
                                    SelectionContainer {
                                        MarkdownBody(message.text, Ink, MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        } else item {
                            if (detailError == null) CircularProgressIndicator()
                            else {
                                Text(detailError!!, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { detailAttempt++ }) { Text("Retry") }
                            }
                        }
                    }
                }
            }
        }
    }
}
