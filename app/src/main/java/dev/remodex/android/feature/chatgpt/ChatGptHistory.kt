package dev.remodex.android.feature.chatgpt

import org.json.JSONObject

data class ChatGptChat(val id: String, val title: String, val updatedAt: String?)
data class ChatGptPage(val items: List<ChatGptChat>, val nextOffset: Int?)
data class ChatGptMessage(val id: String, val role: String, val text: String)
data class ChatGptDetail(val id: String, val title: String, val messages: List<ChatGptMessage>)

internal fun decodeChatGptPage(json: JSONObject, offset: Int): ChatGptPage {
    val array = json.getJSONArray("items")
    val next = if (json.isNull("nextOffset")) null else json.getInt("nextOffset")
    require(next == null || next > offset) { "ChatGPT history pagination did not advance." }
    return ChatGptPage((0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        ChatGptChat(item.getString("id"), item.getString("title"),
            if (item.isNull("updatedAt")) null else item.getString("updatedAt"))
    }.distinctBy { it.id }, next)
}

internal fun decodeChatGptDetail(json: JSONObject, id: String): ChatGptDetail {
    require(json.getString("id") == id) { "Received a different ChatGPT conversation." }
    val array = json.getJSONArray("messages")
    return ChatGptDetail(id, json.getString("title"), (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        ChatGptMessage(item.getString("id"), item.getString("role"), item.getString("text"))
    })
}
