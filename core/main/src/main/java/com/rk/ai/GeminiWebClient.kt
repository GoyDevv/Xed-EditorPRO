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

    /** Sign out / delete the stored Gemini login. */
    fun clear() {
        AiPrefs.geminiPsid = ""
        AiPrefs.geminiPsidts = ""
        AiPrefs.geminiAccount = ""
    }

    fun account(): String = AiPrefs.geminiAccount

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
 * the internal gemini.google.com endpoint the web app uses, streaming the reply in real time. The web
 * API has no native tool-calling, so tools use a text protocol (`<tool_call>{…}</tool_call>`) that is
 * parsed back into real tool calls. Google changes this private protocol often — the Gemini API-key
 * provider stays as the reliable, tool-native path.
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
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

    @Volatile private var currentCall: okhttp3.Call? = null

    fun cancel() {
        runCatching { currentCall?.cancel() }
    }

    /** Consumer Gemini models shown in the picker (curated — the web API has no model-list endpoint). */
    fun models(): List<String> =
        listOf("Gemini 3.1 Pro", "Gemini 3.5 Flash", "Gemini 3.1 Flash Lite")

    private val toolCallRegex = Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
    private val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

    data class Tokens(val snlm0e: String, val bl: String)

    /** Verify the login works (fetch the session token) and detect the account. Returns model list. */
    suspend fun verify(): List<String> {
        fetchTokens()
        return models()
    }

    /** True if we currently have a usable Gemini session (best-effort, quick). */
    suspend fun isReady(): Boolean = runCatching { fetchTokens(); true }.getOrDefault(false)

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
                        ?: throw IOException(
                            "Gemini isn't ready for this account. Open gemini.google.com in the browser, accept the " +
                                "terms / finish setup, then try again."
                        )
                // Best-effort account label.
                if (AiPrefs.geminiAccount.isBlank()) {
                    emailRegex.find(body)?.value?.let { AiPrefs.geminiAccount = it }
                }
                val bl = Regex("\"cfb2h\":\"(.*?)\"").find(body)?.groupValues?.get(1) ?: "boq_assistant-bard-web-server"
                Tokens(snlm0e, bl)
            }
        }

    private fun reqId(): String = "${(10000..99999).random()}${System.currentTimeMillis() % 100000}"

    /** Streaming chat turn — emits text via [onDelta] as it arrives (real-time). */
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
            val form = FormBody.Builder().add("f.req", freq.toString()).add("at", tokens.snlm0e).build()
            val url = "$GENERATE?bl=${tokens.bl}&_reqid=${reqId()}&rt=c"
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
                    if (!resp.isSuccessful) {
                        throw IOException("Gemini web request failed (${resp.code}): ${resp.body.string().take(300)}")
                    }
                    val source = resp.body.source()
                    var emitted = ""
                    var latest = ""
                    val raw = StringBuilder()
                    while (true) {
                        val line = runCatching { source.readUtf8Line() }.getOrNull() ?: break
                        if (raw.length < 4000) raw.append(line).append('\n')
                        val t = line.trim()
                        if (t.length < 2 || !t.startsWith("[")) continue
                        val text = extractFromLine(t) ?: continue
                        if (text.length >= latest.length) {
                            latest = text
                            when {
                                text.length > emitted.length && text.startsWith(emitted) -> {
                                    onDelta(text.substring(emitted.length)); emitted = text
                                }
                                text != emitted -> { onDelta(text); emitted = text }
                            }
                        }
                    }
                    var finalText = latest.ifBlank { emitted }
                    if (finalText.isBlank()) {
                        finalText =
                            "Error: Gemini returned no readable text. Your login may have expired (re-sign in), " +
                                "or the web format changed — use the Gemini API-key provider for reliability. " +
                                "Raw: ${raw.toString().take(200)}"
                        onDelta(finalText)
                    }
                    val (content, toolCalls) = splitToolCalls(finalText)
                    AiClient.ChatResult(
                        AiMessage(role = "assistant", content = content, toolCalls = toolCalls),
                        estimateTokens(prompt) + estimateTokens(finalText),
                    )
                }
            } finally {
                currentCall = null
            }
        }

    private fun estimateTokens(s: String): Int = s.length / 4

    private fun buildPrompt(messages: List<AiMessage>, tools: JSONArray): String {
        val sb = StringBuilder()
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }.trim()
        if (system.isNotEmpty()) sb.append(system).append("\n\n")

        if (tools.length() > 0) {
            sb.append("You can use tools. To call a tool, output EXACTLY this on its own line:\n")
            sb.append("<tool_call>{\"name\":\"TOOL\",\"arguments\":{...}}</tool_call>\n")
            sb.append("Then stop and wait for the tool result before continuing. Available tools:\n")
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

    /** Extract the latest assistant text from a single batchexecute chunk line, or null. */
    private fun extractFromLine(line: String): String? =
        runCatching {
                val arr = JSONArray(line)
                for (i in 0 until arr.length()) {
                    val el = arr.optJSONArray(i) ?: continue
                    if (el.optString(0) != "wrb.fr") continue
                    val payload = el.optString(2, "")
                    if (payload.isBlank()) continue
                    val body = JSONArray(payload)
                    val candidates = body.optJSONArray(4) ?: continue
                    val first = candidates.optJSONArray(0) ?: continue
                    val textArr = first.optJSONArray(1) ?: continue
                    val text = textArr.optString(0, "")
                    if (text.isNotBlank()) return@runCatching text
                }
                null
            }
            .getOrNull()
}
