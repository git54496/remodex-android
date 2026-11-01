package dev.remodex.android.feature.chatgpt

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatGptHistoryTest {
    @Test fun pagePreservesCursorAndDeduplicatesChats() {
        val page = decodeChatGptPage(JSONObject("""{"items":[{"id":"a","title":"One","updatedAt":null},{"id":"a","title":"One"}],"nextOffset":60}"""), 30)
        assertEquals(1, page.items.size)
        assertEquals(60, page.nextOffset)
        assertNull(page.items[0].updatedAt)
    }
    @Test fun emptyLastPageEndsPagination() {
        val page = decodeChatGptPage(JSONObject("""{"items":[],"nextOffset":null}"""), 60)
        assertNull(page.nextOffset)
        assertTrue(page.items.isEmpty())
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsRepeatedCursor() {
        decodeChatGptPage(JSONObject("""{"items":[],"nextOffset":30}"""), 30)
    }
    @Test fun detailRetainsMessageOrderAndMarkdown() {
        val detail = decodeChatGptDetail(JSONObject("""{"id":"a","title":"Chat","messages":[{"id":"u","role":"user","text":"Question"},{"id":"v","role":"assistant","text":"**Answer**"}]}"""), "a")
        assertEquals(listOf("u", "v"), detail.messages.map { it.id })
        assertEquals("**Answer**", detail.messages[1].text)
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnotherConversation() {
        decodeChatGptDetail(JSONObject("""{"id":"b","title":"Chat","messages":[]}"""), "a")
    }
}
