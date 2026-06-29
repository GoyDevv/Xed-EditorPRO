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

    var customBaseUrl: String
        get() = Preference.getString(CUSTOM_BASE_URL, "")
        set(value) = Preference.setString(CUSTOM_BASE_URL, value.trim())

    /** Whether any provider has a key configured. */
    fun hasAnyKey(): Boolean = AiProviders.all.any { hasKey(it.id) }

    /** Effective base URL for a provider (custom uses the stored URL). */
    fun baseUrl(provider: AiProvider): String =
        if (provider.editableBaseUrl) customBaseUrl.ifBlank { provider.baseUrl } else provider.baseUrl

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
}
