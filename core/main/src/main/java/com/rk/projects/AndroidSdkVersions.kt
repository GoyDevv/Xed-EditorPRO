package com.rk.projects

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides real Android SDK platform (API level) versions for the Create Project sheet.
 *
 * Primary source is Google's public SDK repository manifest, so the list stays current (newest
 * first) without hardcoding a single version. If the network is unavailable we fall back to a
 * bundled list of recent API levels so the picker is never empty — and never shows fake data.
 *
 * Values are plain API levels as strings ("35", "34", …).
 */
object AndroidSdkVersions {

    // Google's addon/repository manifests list every installable platform as "platforms;android-NN".
    private val REPO_URLS =
        listOf(
            "https://dl.google.com/android/repository/repository2-3.xml",
            "https://dl.google.com/android/repository/repository2-1.xml",
        )

    /** Recent stable API levels, newest first. A sane offline default. */
    val FALLBACK: List<String> =
        listOf("36", "35", "34", "33", "32", "31", "30", "29", "28", "27", "26", "25", "24", "23", "21")

    private val platformRegex = Regex("platforms;android-(\\d+)")

    /** API level → Android marketing version, for friendly labels in the picker. */
    private val VERSION_NAMES: Map<Int, String> =
        mapOf(
            37 to "Android 17",
            36 to "Android 16",
            35 to "Android 15",
            34 to "Android 14",
            33 to "Android 13",
            32 to "Android 12L",
            31 to "Android 12",
            30 to "Android 11",
            29 to "Android 10",
            28 to "Android 9",
            27 to "Android 8.1",
            26 to "Android 8.0",
            25 to "Android 7.1",
            24 to "Android 7.0",
            23 to "Android 6.0",
            22 to "Android 5.1",
            21 to "Android 5.0",
        )

    /** Marketing name for an API level, or null if unknown. */
    fun androidVersionName(api: Int): String? = VERSION_NAMES[api]

    /** Human label for the picker, e.g. "API 35 · Android 15" (falls back to "API 35"). */
    fun label(api: String): String {
        val name = api.toIntOrNull()?.let { VERSION_NAMES[it] }
        return if (name != null) "API $api · $name" else "API $api"
    }

    /** Extracts the raw API level back out of a [label] produced by [label]. */
    fun apiFromLabel(label: String): String = label.removePrefix("API ").substringBefore(" ").trim()

    /**
     * Fetches installable Android platform API levels from Google's SDK repository (newest first,
     * de-duplicated). Returns [FALLBACK] on any failure. Safe to call from a coroutine; performs IO
     * on [Dispatchers.IO].
     */
    suspend fun fetchApiLevels(): List<String> =
        withContext(Dispatchers.IO) {
            for (url in REPO_URLS) {
                val levels =
                    runCatching {
                            val connection = (URL(url).openConnection() as HttpURLConnection)
                            connection.connectTimeout = 8000
                            connection.readTimeout = 8000
                            connection.requestMethod = "GET"
                            val body =
                                connection.inputStream.use { it.bufferedReader().readText() }.also { connection.disconnect() }
                            platformRegex
                                .findAll(body)
                                .mapNotNull { it.groupValues[1].toIntOrNull() }
                                .distinct()
                                .sortedDescending()
                                .map { it.toString() }
                                .toList()
                        }
                        .getOrDefault(emptyList())
                if (levels.isNotEmpty()) return@withContext levels
            }
            FALLBACK
        }
}
