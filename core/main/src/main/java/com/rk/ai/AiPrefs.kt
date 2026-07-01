package com.rk.ai

import com.rk.settings.Preference

/**
 * Persistence for AI settings, backed by the app's [Preference] store. Keeps API keys per provider,
 * the selected provider/model, an optional custom base URL, and the tool-permission policy.
 *
 * Keys are stored locally only (never sent anywhere except as the Authorization header to that
 * provider's own endpoint).
 */
object AiPrefs {
    private const val KEY_PREFIX = "ai_key_"
    private const val SELECTED_PROVIDER = "ai_selected_provider"
    private const val SELECTED_MODEL = "ai_selected_model"
    private const val CUSTOM_BASE_URL = "ai_custom_base_url"
    private const val BASE_URL_PREFIX = "ai_base_url_" // per-provider editable base URL
    private const val PERMISSION_PREFIX = "ai_perm_" // per-tool: "ask" | "always" | "never"

    fun getKey(providerId: String): String = Preference.getString(KEY_PREFIX + providerId, "")

    fun setKey(providerId: String, key: String) = Preference.setString(KEY_PREFIX + providerId, key.trim())

    fun hasKey(providerId: String): Boolean = getKey(providerId).isNotBlank()

    var selectedProviderId: String
        get() = Preference.getString(SELECTED_PROVIDER, AiProviders.OPENROUTER.id)
        set(value) = Preference.setString(SELECTED_PROVIDER, value)

    var selectedModel: String
        get() = Preference.getString(SELECTED_MODEL, "")
        set(value) = Preference.setString(SELECTED_MODEL, value)

    /**
     * Per-provider editable base URL (used by the Custom provider). The legacy single
     * [customBaseUrl] is migrated transparently for the "custom" provider.
     */
    fun getBaseUrl(providerId: String): String {
        val stored = Preference.getString(BASE_URL_PREFIX + providerId, "")
        if (stored.isNotBlank()) return stored
        // Backward compatibility: the old single custom-base-url pref.
        if (providerId == AiProviders.CUSTOM.id) return Preference.getString(CUSTOM_BASE_URL, "")
        return ""
    }

    fun setBaseUrl(providerId: String, url: String) {
        Preference.setString(BASE_URL_PREFIX + providerId, url.trim())
        if (providerId == AiProviders.CUSTOM.id) Preference.setString(CUSTOM_BASE_URL, url.trim())
    }

    /** Legacy accessor kept for the Custom provider (delegates to the per-provider storage). */
    var customBaseUrl: String
        get() = getBaseUrl(AiProviders.CUSTOM.id)
        set(value) = setBaseUrl(AiProviders.CUSTOM.id, value)

    /** Whether any provider has a key configured. */
    fun hasAnyKey(): Boolean = AiProviders.all.any { hasKey(it.id) }

    /** Effective base URL for a provider (editable providers use their stored URL). */
    fun baseUrl(provider: AiProvider): String =
        if (provider.editableBaseUrl) getBaseUrl(provider.id).ifBlank { provider.baseUrl } else provider.baseUrl

    // --- tool permission policy ---------------------------------------------------------------

    enum class Policy {
        ASK,
        ALWAYS,
        NEVER,
    }

    fun getPolicy(toolName: String): Policy =
        when (Preference.getString(PERMISSION_PREFIX + toolName, "ask")) {
            "always" -> Policy.ALWAYS
            "never" -> Policy.NEVER
            else -> Policy.ASK
        }

    fun setPolicy(toolName: String, policy: Policy) {
        Preference.setString(
            PERMISSION_PREFIX + toolName,
            when (policy) {
                Policy.ALWAYS -> "always"
                Policy.NEVER -> "never"
                Policy.ASK -> "ask"
            },
        )
    }

    // --- MCP (Model Context Protocol) servers -------------------------------------------------
    // Stored as a JSON array of {"name","command"} objects. Each command is run in the Linux
    // sandbox (e.g. "npx -y @modelcontextprotocol/server-filesystem /sdcard") and speaks JSON-RPC
    // over stdio. Empty by default → MCP is completely inert unless the user adds a server.

    private const val MCP_SERVERS = "ai_mcp_servers"

    var mcpServersRaw: String
        get() = Preference.getString(MCP_SERVERS, "")
        set(value) = Preference.setString(MCP_SERVERS, value)

    // --- Gemini (Google login) cookies --------------------------------------------------------
    private const val GEMINI_PSID = "ai_gemini_psid"
    private const val GEMINI_PSIDTS = "ai_gemini_psidts"

    var geminiPsid: String
        get() = Preference.getString(GEMINI_PSID, "")
        set(value) = Preference.setString(GEMINI_PSID, value.trim())

    var geminiPsidts: String
        get() = Preference.getString(GEMINI_PSIDTS, "")
        set(value) = Preference.setString(GEMINI_PSIDTS, value.trim())

    private const val GEMINI_ACCOUNT = "ai_gemini_account"

    /** Human-readable Google account label (email/name) for the Gemini login, if detected. */
    var geminiAccount: String
        get() = Preference.getString(GEMINI_ACCOUNT, "")
        set(value) = Preference.setString(GEMINI_ACCOUNT, value.trim())
}
