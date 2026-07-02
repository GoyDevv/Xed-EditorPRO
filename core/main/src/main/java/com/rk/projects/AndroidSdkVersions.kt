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
        listOf("35", "34", "33", "32", "31", "30", "29", "28", "27", "26", "25", "24", "23", "21")

    private val platformRegex = Regex("platforms;android-(\\d+)")

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
