package com.rk.ai

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Cookie-based auth for consumer Gemini (gemini.google.com), captured via Google login. */
object GeminiWebAuth {
    fun hasCreds(): Boolean = AiPrefs.geminiPsid.isNotBlank()

    fun setCookies(psid: String, psidts: String) {
        AiPrefs.geminiPsid = psid
        AiPrefs.geminiPsidts = psidts
    }

    fun clear() {
        AiPrefs.geminiPsid = ""
        AiPrefs.geminiPsidts = ""
    }

    /** Cookie header for requests to gemini.google.com. */
    fun cookieHeader(): String {
        val parts = ArrayList<String>()
        AiPrefs.geminiPsid.ifBlank { null }?.let { parts.add("__Secure-1PSID=$it") }
        AiPrefs.geminiPsidts.ifBlank { null }?.let { parts.add("__Secure-1PSIDTS=$it") }
        return parts.joinToString("; ")
    }
}

/**
 * EXPERIMENTAL native client for consumer Gemini via Google-account cookies (no API key). Talks to
 * the internal gemini.google.com endpoint the web app uses. The web API has **no native
 * tool-calling**, so tools are exposed to the model via a text protocol (`<tool_call>{…}</tool_call>`)
 * and parsed back out. Google changes this private protocol frequently — expect breakage; the Gemini
 * API-key provider remains the reliable, tool-native path.
 */
object GeminiWebClient {
    private const val HOST = "https://gemini.google.com"
    private const val GENERATE =
        "$HOST/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()

    @Volatile private var currentCall: okhttp3.Call? = null

    fun cancel() {
        runCatching { currentCall?.cancel() }
    }

    fun models(): List<String> = listOf("gemini")

    private val toolCallRegex = Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)

    /** Verify the login works by fetching the session token. */
    suspend fun verify(): List<String> {
        fetchTokens()
        return models()
    }

    data class Tokens(val snlm0e: String, val bl: String)

    private suspend fun fetchTokens(): Tokens =
        withContext(Dispatchers.IO) {
            if (!GeminiWebAuth.hasCreds()) throw IOException("Not signed in to Gemini. Use Google login first.")
            val req =
                Request.Builder()
                    .url("$HOST/app")
                    .addHeader("Cookie", GeminiWebAuth.cookieHeader())
                    .addHeader("User-Agent", UA)
                    .get()
                    .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) throw IOException("Gemini session failed (${resp.code}). Re-login may be required.")
                val snlm0e =
                    Regex("\"SNlM0e\":\"(.*?)\"").find(body)?.groupValues?.get(1)
                        ?: throw IOException("Could not get Gemini session token (login may be expired). Re-login.")
                val bl = Regex("\"cfb2h\":\"(.*?)\"").find(body)?.groupValues?.get(1) ?: "boq_assistant-bard-web-server"
                Tokens(snlm0e, bl)
            }
        }

    /**
     * Streaming chat turn (the web API returns the whole answer at once, delivered via [onDelta] in
     * chunks). Tools are described in text and parsed from `<tool_call>` blocks.
     */
    suspend fun chatStream(
        model: String,
        messages: List<AiMessage>,
        tools: JSONArray,
        onDelta: (String) -> Unit,
    ): AiClient.ChatResult =
        withContext(Dispatchers.IO) {
            val tokens = fetchTokens()
            val prompt = buildPrompt(messages, tools)

            val inner = JSONArray().put(JSONArray().put(prompt)).put(JSONObject.NULL).put(JSONObject.NULL)
            val freq = JSONArray().put(JSONObject.NULL).put(inner.toString())

            val form =
                FormBody.Builder().add("f.req", freq.toString()).add("at", tokens.snlm0e).build()
            val url = "$GENERATE?bl=${tokens.bl}&_reqid=${(1000..9999).random()}${System.currentTimeMillis() % 100000}&rt=c"
            val req =
                Request.Builder()
                    .url(url)
                    .addHeader("Cookie", GeminiWebAuth.cookieHeader())
                    .addHeader("User-Agent", UA)
                    .addHeader("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                    .post(form)
                    .build()

            val call = client.newCall(req)
            currentCall = call
            try {
                call.execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) throw IOException("Gemini web request failed (${resp.code}): ${body.take(300)}")
                    val raw = extractText(body)
                    val (content, toolCalls) = splitToolCalls(raw)
                    if (content.isNotEmpty()) onDelta(content)
                    AiClient.ChatResult(
                        AiMessage(role = "assistant", content = content, toolCalls = toolCalls),
                        estimateTokens(prompt) + estimateTokens(raw),
                    )
                }
            } finally {
                currentCall = null
            }
        }

    private fun estimateTokens(s: String): Int = s.length / 4

    /** Flatten the conversation into a single prompt and describe tools in text. */
    private fun buildPrompt(messages: List<AiMessage>, tools: JSONArray): String {
        val sb = StringBuilder()
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }.trim()
        if (system.isNotEmpty()) sb.append(system).append("\n\n")

        if (tools.length() > 0) {
            sb.append("You can use tools. To call a tool, output EXACTLY one line per call in this format ")
            sb.append("(and nothing else on that line):\n")
            sb.append("<tool_call>{\"name\":\"TOOL\",\"arguments\":{...}}</tool_call>\n")
            sb.append("Wait for the tool result before continuing. Available tools:\n")
            for (i in 0 until tools.length()) {
                val fn = tools.optJSONObject(i)?.optJSONObject("function") ?: continue
                sb.append("- ").append(fn.optString("name")).append(": ").append(fn.optString("description")).append("\n")
            }
            sb.append("\n")
        }

        for (m in messages) {
            when (m.role) {
                "system" -> {}
                "user" -> sb.append("User: ").append(m.content).append("\n")
                "assistant" -> {
                    if (m.content.isNotBlank()) sb.append("Assistant: ").append(m.content).append("\n")
                    for (c in m.toolCalls) {
                        sb.append("<tool_call>{\"name\":\"").append(c.name).append("\",\"arguments\":")
                            .append(if (c.arguments.isBlank()) "{}" else c.arguments).append("}</tool_call>\n")
                    }
                }
                "tool" -> sb.append("Tool result (").append(m.toolCallId ?: "").append("): ").append(m.content).append("\n")
            }
        }
        sb.append("Assistant: ")
        return sb.toString()
    }

    /** Pull `<tool_call>` blocks out of the text and turn them into AiToolCalls. */
    private fun splitToolCalls(text: String): Pair<String, List<AiToolCall>> {
        val calls = ArrayList<AiToolCall>()
        toolCallRegex.findAll(text).forEach { m ->
            val json = runCatching { JSONObject(m.groupValues[1]) }.getOrNull() ?: return@forEach
            val name = json.optString("name")
            if (name.isBlank()) return@forEach
            val args = json.opt("arguments")
            val argStr =
                when (args) {
                    is JSONObject -> args.toString()
                    is String -> args
                    else -> "{}"
                }
            calls.add(AiToolCall(id = "gw_" + UUID.randomUUID().toString().take(8), name = name, arguments = argStr))
        }
        val content = toolCallRegex.replace(text, "").trim()
        return content to calls
    }

    /**
     * Extract the assistant text from the batchexecute response. Best-effort against the internal
     * format: skip the ")]}'" prelude, scan each line's JSON for a ["wrb.fr", …, "<payload>"] entry,
     * parse the payload and read candidates[4][0][1][0]. Falls back to any readable text.
     */
    private fun extractText(raw: String): String {
        val cleaned = raw.trimStart().removePrefix(")]}'").trim()
        for (line in cleaned.split("\n")) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("[")) continue
            val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val el = arr.optJSONArray(i) ?: continue
                if (el.optString(0) != "wrb.fr") continue
                val payload = el.optString(2, "")
                if (payload.isBlank()) continue
                val body = runCatching { JSONArray(payload) }.getOrNull() ?: continue
                val candidates = body.optJSONArray(4) ?: continue
                val first = candidates.optJSONArray(0) ?: continue
                val textArr = first.optJSONArray(1) ?: continue
                val text = textArr.optString(0, "")
                if (text.isNotBlank()) return text
            }
        }
        return "Error: could not parse the Gemini web response (the internal format may have changed). " +
            "Try re-logging in, or use the Gemini API-key provider."
    }
}
