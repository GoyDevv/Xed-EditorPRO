package com.rk.ai

import com.rk.utils.application
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-disk persistence for AI chat sessions, so conversations survive app restarts (the user
 * explicitly wanted chats never to be lost). Stored as a single JSON file in the app's files dir.
 *
 * Pure IO — call from a background thread. [AiViewModel] loads on init and saves after changes.
 */
object AiSessionStore {

    data class StoredSession(
        val id: String,
        val title: String,
        val totalTokens: Int,
        val messages: List<AiMessage>,
    )

    private fun file(): File = File(application!!.filesDir, "ai_sessions.json")

    fun loadAll(): List<StoredSession> =
        runCatching {
                val f = file()
                if (!f.exists()) return emptyList()
                val root = JSONObject(f.readText())
                val arr = root.optJSONArray("sessions") ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    StoredSession(
                        id = o.optString("id"),
                        title = o.optString("title", "Chat"),
                        totalTokens = o.optInt("totalTokens", 0),
                        messages = parseMessages(o.optJSONArray("messages")),
                    )
                }
            }
            .getOrElse { emptyList() }

    fun saveAll(sessions: List<StoredSession>) {
        runCatching {
            val arr = JSONArray()
            for (s in sessions) {
                arr.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("title", s.title)
                        put("totalTokens", s.totalTokens)
                        put("messages", messagesToJson(s.messages))
                    }
                )
            }
            file().writeText(JSONObject().apply { put("sessions", arr) }.toString())
        }
    }

    private fun messagesToJson(messages: List<AiMessage>): JSONArray {
        val arr = JSONArray()
        for (m in messages) {
            val o = JSONObject()
            o.put("role", m.role)
            o.put("content", m.content)
            m.toolCallId?.let { o.put("toolCallId", it) }
            o.put("ui", m.ui)
            if (m.toolCalls.isNotEmpty()) {
                val tc = JSONArray()
                for (c in m.toolCalls) {
                    tc.put(
                        JSONObject().apply {
                            put("id", c.id)
                            put("name", c.name)
                            put("arguments", c.arguments)
                        }
                    )
                }
                o.put("toolCalls", tc)
            }
            arr.put(o)
        }
        return arr
    }

    private fun parseMessages(arr: JSONArray?): List<AiMessage> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val tcArr = o.optJSONArray("toolCalls")
            val toolCalls =
                if (tcArr == null) emptyList()
                else
                    (0 until tcArr.length()).mapNotNull { j ->
                        val c = tcArr.optJSONObject(j) ?: return@mapNotNull null
                        AiToolCall(c.optString("id"), c.optString("name"), c.optString("arguments", "{}"))
                    }
            AiMessage(
                role = o.optString("role"),
                content = o.optString("content"),
                toolCalls = toolCalls,
                toolCallId = if (o.has("toolCallId")) o.optString("toolCallId") else null,
                ui = o.optString("ui", "text"),
            )
        }
    }
}
