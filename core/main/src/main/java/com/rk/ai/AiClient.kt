package com.rk.ai

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal OpenAI-compatible REST client (works with OpenAI/Codex, OpenRouter, Gemini's OpenAI-compat
 * endpoint, and custom gateways). Uses OkHttp + org.json (no extra dependencies).
 *
 * Tool calling is the standard OpenAI "function calling" protocol: we send a `tools` array of JSON
 * schemas; the model replies with `tool_calls`; the caller executes them and sends back `tool`
 * messages. The agent loop lives in [AiAgent].
 */
object AiClient {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    @Volatile private var currentCall: okhttp3.Call? = null

    /** Cancels the in-flight chat request (used by stop). */
    fun cancel() {
        runCatching { currentCall?.cancel() }
        runCatching { KiroClient.cancel() }
    }

    data class ChatResult(val message: AiMessage, val totalTokens: Int)

    /** Verifies the key and lists model ids via GET {base}/models. Throws on failure. */
    suspend fun listModels(provider: AiProvider, key: String): List<String> =
        withContext(Dispatchers.IO) {
            if (provider.id == AiProviders.KIRO.id && AiPrefs.getBaseUrl(provider.id).isBlank()) {
                return@withContext KiroClient.verify()
            }
            val req =
                Request.Builder()
                    .url("${AiPrefs.baseUrl(provider).trimEnd('/')}/models")
                    .addHeader("Authorization", "Bearer $key")
                    .get()
                    .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) throw IOException("Models request failed (${resp.code}): ${body.take(300)}")
                val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                (0 until data.length())
                    .mapNotNull { data.optJSONObject(it)?.optString("id")?.ifBlank { null } }
                    .sorted()
            }
        }

    /** One chat-completions turn with optional tools. Returns the assistant message + token usage. */
    suspend fun chat(
        provider: AiProvider,
        key: String,
        model: String,
        messages: List<AiMessage>,
        tools: JSONArray,
    ): ChatResult =
        withContext(Dispatchers.IO) {
            val payload =
                JSONObject().apply {
                    put("model", model)
                    put("messages", messagesToJson(messages))
                    if (tools.length() > 0) {
                        put("tools", tools)
                        put("tool_choice", "auto")
                    }
                }
            val req =
                Request.Builder()
                    .url("${AiPrefs.baseUrl(provider).trimEnd('/')}/chat/completions")
                    .addHeader("Authorization", "Bearer $key")
                    .post(payload.toString().toRequestBody(JSON))
                    .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) throw IOException("Chat failed (${resp.code}): ${body.take(600)}")
                val json = JSONObject(body)
                val choices = json.optJSONArray("choices") ?: throw IOException("No choices in response")
                val message = choices.getJSONObject(0).getJSONObject("message")
                val content = message.optString("content", "")
                val toolCalls = parseToolCalls(message.optJSONArray("tool_calls"))
                val total = json.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0
                ChatResult(AiMessage(role = "assistant", content = content, toolCalls = toolCalls), total)
            }
        }

    /** Streaming variant: invokes [onDelta] with text fragments as they arrive. Returns final message + usage. */
    suspend fun chatStream(
        provider: AiProvider,
        key: String,
        model: String,
        messages: List<AiMessage>,
        tools: JSONArray,
        onDelta: (String) -> Unit,
    ): ChatResult =
        withContext(Dispatchers.IO) {
            // Kiro native mode: talk to AWS CodeWhisperer directly (no gateway) when no base URL is set.
            if (provider.id == AiProviders.KIRO.id && AiPrefs.getBaseUrl(provider.id).isBlank()) {
                return@withContext KiroClient.chatStream(model, messages, tools, onDelta)
            }
            val payload =
                JSONObject().apply {
                    put("model", model)
                    put("messages", messagesToJson(messages))
                    if (tools.length() > 0) {
                        put("tools", tools)
                        put("tool_choice", "auto")
                    }
                    put("stream", true)
                    put("stream_options", JSONObject().put("include_usage", true))
                }
            val req =
                Request.Builder()
                    .url("${AiPrefs.baseUrl(provider).trimEnd('/')}/chat/completions")
                    .addHeader("Authorization", "Bearer $key")
                    .post(payload.toString().toRequestBody(JSON))
                    .build()
            val call = client.newCall(req)
            currentCall = call
            try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val b = resp.body.string()
                    throw IOException("Chat failed (${resp.code}): ${b.take(600)}")
                }
                val source = resp.body.source()
                val content = StringBuilder()
                var total = 0
                // Accumulate streamed tool-call fragments by index.
                val toolNames = HashMap<Int, String>()
                val toolIds = HashMap<Int, String>()
                val toolArgs = HashMap<Int, StringBuilder>()
                val order = ArrayList<Int>()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank() || !line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    json.optJSONObject("usage")?.let { total = it.optInt("total_tokens", total) }
                    val choices = json.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                    val frag = delta.optString("content", "")
                    if (frag.isNotEmpty()) {
                        content.append(frag)
                        onDelta(frag)
                    }
                    delta.optJSONArray("tool_calls")?.let { tcs ->
                        for (k in 0 until tcs.length()) {
                            val t = tcs.getJSONObject(k)
                            val idx = t.optInt("index", k)
                            if (!order.contains(idx)) order.add(idx)
                            if (t.has("id")) toolIds[idx] = t.optString("id")
                            t.optJSONObject("function")?.let { fn ->
                                if (fn.has("name")) toolNames[idx] = fn.optString("name")
                                toolArgs.getOrPut(idx) { StringBuilder() }.append(fn.optString("arguments", ""))
                            }
                        }
                    }
                }
                val toolCalls =
                    order
                        .map { idx ->
                            AiToolCall(
                                id = toolIds[idx] ?: "call_$idx",
                                name = toolNames[idx] ?: "",
                                arguments = toolArgs[idx]?.toString()?.ifBlank { "{}" } ?: "{}",
                            )
                        }
                        .filter { it.name.isNotBlank() }
                ChatResult(AiMessage(role = "assistant", content = content.toString(), toolCalls = toolCalls), total)
            }
            } finally {
                currentCall = null
            }
        }

    private fun messagesToJson(messages: List<AiMessage>): JSONArray {
        val arr = JSONArray()
        for (m in messages) {
            val o = JSONObject()
            o.put("role", m.role)
            o.put("content", m.content)
            if (m.role == "tool" && m.toolCallId != null) o.put("tool_call_id", m.toolCallId)
            if (m.toolCalls.isNotEmpty()) {
                val tc = JSONArray()
                for (call in m.toolCalls) {
                    tc.put(
                        JSONObject().apply {
                            put("id", call.id)
                            put("type", "function")
                            put(
                                "function",
                                JSONObject().apply {
                                    put("name", call.name)
                                    put("arguments", call.arguments)
                                },
                            )
                        }
                    )
                }
                o.put("tool_calls", tc)
            }
            arr.put(o)
        }
        return arr
    }

    private fun parseToolCalls(arr: JSONArray?): List<AiToolCall> {
        if (arr == null) return emptyList()
        val out = ArrayList<AiToolCall>()
        for (i in 0 until arr.length()) {
            val call = arr.optJSONObject(i) ?: continue
            val fn = call.optJSONObject("function") ?: continue
            out.add(
                AiToolCall(
                    id = call.optString("id", "call_$i"),
                    name = fn.optString("name", ""),
                    arguments = fn.optString("arguments", "{}"),
                )
            )
        }
        return out
    }
}
