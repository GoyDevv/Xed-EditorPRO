package com.rk.ai

import java.io.EOFException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Kiro chat client — talks directly to AWS CodeWhisperer's `generateAssistantResponse`
 * endpoint (the same one Kiro IDE uses) with the access token from [KiroAuth], so the agent uses
 * Kiro with no external gateway process. Translates the app's [AiMessage] conversation into Kiro's
 * `conversationState` schema and decodes the binary `application/vnd.amazon.eventstream` response
 * into streamed text + tool calls.
 *
 * Protocol ported from the open-source kiro-gateway (jwadow/kiro-gateway, AGPL).
 */
object KiroClient {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    @Volatile private var currentCall: okhttp3.Call? = null

    fun cancel() {
        runCatching { currentCall?.cancel() }
    }

    /** Models exposed for Kiro native mode (Kiro normalizes the id server-side). */
    fun models(): List<String> =
        listOf(
            "auto",
            "claude-sonnet-4",
            "claude-sonnet-4.5",
            "claude-haiku-4.5",
            "claude-opus-4.5",
            "claude-3.7-sonnet",
        )

    private fun resolveModelId(model: String): String =
        when (model.lowercase().replace("_", "-").replace(" ", "-")) {
            "claude-3.7-sonnet", "claude-3-7-sonnet" -> "CLAUDE_3_7_SONNET_20250219_V1_0"
            "" -> "claude-sonnet-4"
            else -> model
        }

    /** Verify Kiro is usable by obtaining a token; returns the model list. */
    suspend fun verify(): List<String> {
        KiroAuth.getAccessToken()
        return models()
    }

    /**
     * One streaming chat turn. Mirrors [AiClient.chatStream]: streams text via [onDelta] and returns
     * the final assistant message (content + tool calls) plus a (best-effort) token count.
     */
    suspend fun chatStream(
        model: String,
        messages: List<AiMessage>,
        tools: JSONArray,
        onDelta: (String) -> Unit,
    ): AiClient.ChatResult =
        withContext(Dispatchers.IO) {
            val token = KiroAuth.getAccessToken()
            val modelId = resolveModelId(model)
            val conversationId = UUID.randomUUID().toString()
            val payload = buildPayload(messages, modelId, tools, conversationId, KiroAuth.currentProfileArn())

            val req =
                Request.Builder()
                    .url("${KiroAuth.apiHost()}/generateAssistantResponse")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/vnd.amazon.eventstream")
                    .addHeader("User-Agent", "KiroIDE")
                    .post(payload.toString().toRequestBody(JSON))
                    .build()

            val call = client.newCall(req)
            currentCall = call
            try {
                call.execute().use { resp ->
                    if (resp.code == 403) {
                        // Token may have just expired — refresh once and retry.
                        runCatching { KiroAuth.forceRefresh() }
                    }
                    if (!resp.isSuccessful) {
                        val b = resp.body.string()
                        throw IOException("Kiro chat failed (${resp.code}): ${b.take(600)}")
                    }
                    val source = resp.body.source()
                    val content = StringBuilder()
                    val toolNames = HashMap<String, String>()
                    val toolInputs = HashMap<String, StringBuilder>()
                    val toolOrder = ArrayList<String>()

                    decodeEventStream(source) { eventType, json ->
                        when {
                            eventType == "assistantResponseEvent" || json.has("content") -> {
                                val frag = json.optString("content", "")
                                if (frag.isNotEmpty()) {
                                    content.append(frag)
                                    onDelta(frag)
                                }
                            }
                            eventType == "toolUseEvent" || json.has("toolUseId") -> {
                                val id = json.optString("toolUseId", "")
                                if (id.isNotBlank()) {
                                    if (!toolOrder.contains(id)) toolOrder.add(id)
                                    json.optString("name", "").ifBlank { null }?.let { toolNames[id] = it }
                                    val inputFrag = json.opt("input")
                                    if (inputFrag is String && inputFrag.isNotEmpty()) {
                                        toolInputs.getOrPut(id) { StringBuilder() }.append(inputFrag)
                                    } else if (inputFrag is JSONObject) {
                                        toolInputs[id] = StringBuilder(inputFrag.toString())
                                    }
                                }
                            }
                        }
                    }

                    val toolCalls =
                        toolOrder
                            .mapNotNull { id ->
                                val name = toolNames[id] ?: return@mapNotNull null
                                val args = toolInputs[id]?.toString()?.ifBlank { "{}" } ?: "{}"
                                AiToolCall(id = id, name = name, arguments = args)
                            }

                    val total = estimateTokens(messages) + estimateTokens(content.toString())
                    AiClient.ChatResult(
                        AiMessage(role = "assistant", content = content.toString(), toolCalls = toolCalls),
                        total,
                    )
                }
            } finally {
                currentCall = null
            }
        }

    /** Very rough token estimate (~4 chars/token) for the "% used" gauge in native mode. */
    private fun estimateTokens(text: String): Int = (text.length / 4)

    private fun estimateTokens(messages: List<AiMessage>): Int =
        messages.sumOf { (it.content.length / 4) + it.toolCalls.sumOf { c -> c.arguments.length / 4 } }

    // --- Request building (OpenAI-style AiMessage -> Kiro conversationState) ----------------------

    private fun buildPayload(
        messages: List<AiMessage>,
        modelId: String,
        tools: JSONArray,
        conversationId: String,
        profileArn: String?,
    ): JSONObject {
        val systemPrompt =
            messages.filter { it.role == "system" }.joinToString("\n") { it.content }.trim()

        // Build an ordered list of history entries; consecutive tool results merge into one user turn.
        data class Entry(val obj: JSONObject, val isUser: Boolean, var userContent: String)
        val entries = ArrayList<Entry>()
        val pendingToolResults = JSONArray()

        fun flushToolResults() {
            if (pendingToolResults.length() == 0) return
            val ctx = JSONObject().put("toolResults", JSONArray(pendingToolResults.toString()))
            val uim =
                JSONObject()
                    .put("content", "(tool results)")
                    .put("modelId", modelId)
                    .put("origin", "AI_EDITOR")
                    .put("userInputMessageContext", ctx)
            entries.add(Entry(JSONObject().put("userInputMessage", uim), true, "(tool results)"))
            // clear
            while (pendingToolResults.length() > 0) pendingToolResults.remove(0)
        }

        for (msg in messages) {
            when (msg.role) {
                "system" -> {}
                "tool" -> {
                    pendingToolResults.put(
                        JSONObject()
                            .put("content", JSONArray().put(JSONObject().put("text", msg.content.ifBlank { "(empty result)" })))
                            .put("status", "success")
                            .put("toolUseId", msg.toolCallId ?: "")
                    )
                }
                "assistant" -> {
                    flushToolResults()
                    val ar = JSONObject().put("content", msg.content.ifBlank { "(empty)" })
                    if (msg.toolCalls.isNotEmpty()) {
                        val uses = JSONArray()
                        for (c in msg.toolCalls) {
                            val input = runCatching { JSONObject(c.arguments) }.getOrDefault(JSONObject())
                            uses.put(JSONObject().put("name", c.name).put("input", input).put("toolUseId", c.id))
                        }
                        ar.put("toolUses", uses)
                    }
                    entries.add(Entry(JSONObject().put("assistantResponseMessage", ar), false, ""))
                }
                else -> { // user
                    flushToolResults()
                    val uim =
                        JSONObject()
                            .put("content", msg.content.ifBlank { "(empty)" })
                            .put("modelId", modelId)
                            .put("origin", "AI_EDITOR")
                    entries.add(Entry(JSONObject().put("userInputMessage", uim), true, msg.content))
                }
            }
        }
        flushToolResults()

        if (entries.isEmpty()) throw IOException("No messages to send to Kiro")

        // The last entry is the current message; the rest is history.
        var current = entries.removeAt(entries.size - 1)

        // Kiro expects the current message to be a userInputMessage.
        if (!current.isUser) {
            entries.add(current)
            val uim =
                JSONObject().put("content", "Continue").put("modelId", modelId).put("origin", "AI_EDITOR")
            current = Entry(JSONObject().put("userInputMessage", uim), true, "Continue")
        }

        val history = JSONArray()
        // Prepend the system prompt to the first user turn in history, else to the current message.
        var systemInjected = false
        if (systemPrompt.isNotEmpty()) {
            val firstUser = entries.firstOrNull { it.isUser }
            if (firstUser != null) {
                val uim = firstUser.obj.getJSONObject("userInputMessage")
                uim.put("content", "$systemPrompt\n\n${firstUser.userContent.ifBlank { "(empty)" }}")
                systemInjected = true
            }
        }
        for (e in entries) history.put(e.obj)

        val currentUim = current.obj.getJSONObject("userInputMessage")
        if (systemPrompt.isNotEmpty() && !systemInjected) {
            currentUim.put("content", "$systemPrompt\n\n${current.userContent.ifBlank { "Continue" }}")
        }

        // Attach tools to the current message context.
        if (tools.length() > 0) {
            val kiroTools = JSONArray()
            for (i in 0 until tools.length()) {
                val fn = tools.optJSONObject(i)?.optJSONObject("function") ?: continue
                val schema = sanitizeSchema(fn.optJSONObject("parameters") ?: JSONObject())
                val desc = fn.optString("description", "").ifBlank { "Tool: ${fn.optString("name")}" }
                kiroTools.put(
                    JSONObject().put(
                        "toolSpecification",
                        JSONObject()
                            .put("name", fn.optString("name"))
                            .put("description", desc)
                            .put("inputSchema", JSONObject().put("json", schema)),
                    )
                )
            }
            if (kiroTools.length() > 0) {
                val ctx = currentUim.optJSONObject("userInputMessageContext") ?: JSONObject()
                ctx.put("tools", kiroTools)
                currentUim.put("userInputMessageContext", ctx)
            }
        }

        val conversationState =
            JSONObject()
                .put("chatTriggerType", "MANUAL")
                .put("conversationId", conversationId)
                .put("currentMessage", JSONObject().put("userInputMessage", currentUim))
        if (history.length() > 0) conversationState.put("history", history)

        val payload = JSONObject().put("conversationState", conversationState)
        if (!profileArn.isNullOrBlank()) payload.put("profileArn", profileArn)
        return payload
    }

    /** Kiro rejects empty `required` arrays and `additionalProperties`; strip them recursively. */
    private fun sanitizeSchema(schema: JSONObject): JSONObject {
        val out = JSONObject()
        for (key in schema.keys()) {
            if (key == "additionalProperties") continue
            val v = schema.get(key)
            if (key == "required" && v is JSONArray && v.length() == 0) continue
            when (v) {
                is JSONObject -> out.put(key, sanitizeSchema(v))
                is JSONArray -> {
                    val arr = JSONArray()
                    for (i in 0 until v.length()) {
                        val item = v.get(i)
                        arr.put(if (item is JSONObject) sanitizeSchema(item) else item)
                    }
                    out.put(key, arr)
                }
                else -> out.put(key, v)
            }
        }
        return out
    }

    // --- AWS event-stream binary decoder ----------------------------------------------------------
    // Frame: [totalLen:4][headerLen:4][preludeCrc:4][headers][payload][msgCrc:4] (all big-endian).
    // Header: [nameLen:1][name][valueType:1][value]; string values are [len:2][bytes] (type 7).

    private fun decodeEventStream(source: BufferedSource, onEvent: (eventType: String, payload: JSONObject) -> Unit) {
        try {
            while (true) {
                if (source.exhausted()) break
                val totalLen = source.readInt()
                val headerLen = source.readInt()
                source.readInt() // prelude CRC (ignored)
                val headerBytes = if (headerLen > 0) source.readByteArray(headerLen.toLong()) else ByteArray(0)
                val payloadLen = totalLen - headerLen - 16
                val payloadBytes = if (payloadLen > 0) source.readByteArray(payloadLen.toLong()) else ByteArray(0)
                source.readInt() // message CRC (ignored)

                val eventType = parseEventType(headerBytes)
                if (payloadBytes.isEmpty()) continue
                val json = runCatching { JSONObject(String(payloadBytes, Charsets.UTF_8)) }.getOrNull() ?: continue
                onEvent(eventType, json)
            }
        } catch (e: EOFException) {
            // Stream ended mid-frame; treat as end of response.
        }
    }

    private fun parseEventType(header: ByteArray): String {
        var i = 0
        while (i < header.size) {
            val nameLen = header[i].toInt() and 0xff
            i++
            if (i + nameLen > header.size) break
            val name = String(header, i, nameLen, Charsets.UTF_8)
            i += nameLen
            if (i >= header.size) break
            val type = header[i].toInt() and 0xff
            i++
            val value: String? =
                when (type) {
                    6, 7 -> { // byte array / string: 2-byte length prefix
                        if (i + 2 > header.size) return ""
                        val len = ((header[i].toInt() and 0xff) shl 8) or (header[i + 1].toInt() and 0xff)
                        i += 2
                        if (i + len > header.size) return ""
                        val s = String(header, i, len, Charsets.UTF_8)
                        i += len
                        s
                    }
                    0, 1 -> null // bool true/false (no value bytes)
                    2 -> { i += 1; null } // byte
                    3 -> { i += 2; null } // short
                    4 -> { i += 4; null } // int
                    5, 8 -> { i += 8; null } // long / timestamp
                    9 -> { i += 16; null } // uuid
                    else -> return ""
                }
            if (name == ":event-type" && value != null) return value
        }
        return ""
    }
}
