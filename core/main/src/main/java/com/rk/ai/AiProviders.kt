package com.rk.ai

/**
 * An AI provider that speaks the OpenAI-compatible REST shape:
 *   GET  {baseUrl}/models
 *   POST {baseUrl}/chat/completions   (messages + tools + usage, Bearer auth)
 *
 * This single shape covers OpenAI/Codex, OpenRouter, Google Gemini (its OpenAI-compat endpoint),
 * and any custom/self-hosted gateway — which is exactly how tools like OpenCode let one key drive
 * many models with client-side tool (function) calling.
 *
 * @param id stable key used for persistence.
 * @param label shown in the UI.
 * @param baseUrl OpenAI-compatible base (no trailing slash, no /chat/completions).
 * @param editableBaseUrl whether the user can change the base URL (true for Custom).
 * @param defaultModel a sensible default model id (may be replaced by the live /models list).
 * @param signupUrl where to get a key (shown in the add-key dialog).
 */
data class AiProvider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val editableBaseUrl: Boolean = false,
    val defaultModel: String = "",
    val signupUrl: String = "",
)

object AiProviders {
    val OPENROUTER =
        AiProvider(
            id = "openrouter",
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "openai/gpt-4o-mini",
            signupUrl = "https://openrouter.ai/keys",
        )

    val OPENAI =
        AiProvider(
            id = "openai",
            label = "OpenAI (Codex / GPT)",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            signupUrl = "https://platform.openai.com/api-keys",
        )

    val GEMINI =
        AiProvider(
            id = "gemini",
            label = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            defaultModel = "gemini-1.5-flash",
            signupUrl = "https://aistudio.google.com/app/apikey",
        )

    /**
     * Gemini via **Google account login** (consumer gemini.google.com), instead of an API key. The
     * user signs in with Google in a WebView; the app captures the Gemini web cookies and talks to
     * the same internal endpoint the Gemini app uses (free/consumer limits). Experimental: the web
     * API has no native tool-calling, so tools use a text protocol, and Google may change it.
     */
    val GEMINI_WEB =
        AiProvider(
            id = "gemini_web",
            label = "Gemini (Google login)",
            baseUrl = "",
            defaultModel = "Gemini 3.5 Flash",
            signupUrl = "https://gemini.google.com",
        )

    val CUSTOM =
        AiProvider(
            id = "custom",
            label = "Custom (OpenAI-compatible)",
            baseUrl = "",
            editableBaseUrl = true,
            defaultModel = "",
            signupUrl = "",
        )

    val all = listOf(OPENROUTER, OPENAI, GEMINI, GEMINI_WEB, CUSTOM)

    fun byId(id: String?): AiProvider = all.firstOrNull { it.id == id } ?: OPENROUTER

    /** Rough context window (tokens) for known models, used to show "% used". Default 128k. */
    fun contextWindow(model: String): Int {
        val m = model.lowercase()
        return when {
            m.contains("gpt-4o") || m.contains("gpt-4.1") -> 128_000
            m.contains("o1") || m.contains("o3") -> 200_000
            m.contains("claude") -> 200_000
            m.contains("gemini-1.5") || m.contains("gemini-2") || m.contains("gemini") -> 1_000_000
            m.contains("gpt-3.5") -> 16_385
            else -> 128_000
        }
    }
}
