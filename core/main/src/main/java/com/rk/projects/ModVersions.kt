package com.rk.projects

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resolves real, currently-published Fabric and Forge versions for a given Minecraft version, so the
 * generated mod projects actually resolve their dependencies instead of shipping guessed numbers.
 *
 * These are blocking HTTP calls — call them from an IO context (the scaffolder already runs on
 * [kotlinx.coroutines.Dispatchers.IO]). Every lookup falls back to a sane default on any failure, so
 * scaffolding never breaks when offline (it just produces the same placeholder it used to).
 */
object ModVersions {

    /** Fabric dependency versions for a Minecraft version. [online] is false if any value fell back. */
    data class FabricPins(val yarn: String, val loader: String, val fabricApi: String, val online: Boolean)

    private fun httpGet(urlStr: String): String? =
        runCatching {
                val c = (URL(urlStr).openConnection() as HttpURLConnection)
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.requestMethod = "GET"
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("User-Agent", "Xed-Editor (project scaffolder)")
                val code = c.responseCode
                if (code !in 200..299) {
                    c.disconnect()
                    null
                } else {
                    c.inputStream.use { it.bufferedReader().readText() }.also { c.disconnect() }
                }
            }
            .getOrNull()

    /**
     * Real Fabric versions for [mc]:
     *  - yarn mappings + loader from Fabric's official meta API,
     *  - Fabric API from Modrinth (filtered to the game version).
     */
    fun fabricPins(mc: String): FabricPins {
        var online = true

        val yarn =
            runCatching {
                    val arr = JSONArray(httpGet("https://meta.fabricmc.net/v2/versions/yarn/$mc"))
                    if (arr.length() > 0) arr.getJSONObject(0).getString("version") else null
                }
                .getOrNull() ?: run {
                    online = false
                    "$mc+build.1"
                }

        val loader =
            runCatching {
                    val arr = JSONArray(httpGet("https://meta.fabricmc.net/v2/versions/loader"))
                    var stable: String? = null
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (o.optBoolean("stable", false)) {
                            stable = o.getString("version")
                            break
                        }
                    }
                    stable ?: if (arr.length() > 0) arr.getJSONObject(0).getString("version") else null
                }
                .getOrNull() ?: run {
                    online = false
                    "0.16.9"
                }

        val api =
            runCatching {
                    val gv = URLEncoder.encode("[\"$mc\"]", "UTF-8")
                    val ld = URLEncoder.encode("[\"fabric\"]", "UTF-8")
                    val arr =
                        JSONArray(
                            httpGet("https://api.modrinth.com/v2/project/fabric-api/version?game_versions=$gv&loaders=$ld")
                        )
                    if (arr.length() > 0) arr.getJSONObject(0).getString("version_number") else null
                }
                .getOrNull() ?: run {
                    online = false
                    "0.100.0+$mc"
                }

        return FabricPins(yarn = yarn, loader = loader, fabricApi = api, online = online)
    }

    /**
     * Real Forge build for [mc] from the official promotions manifest (recommended, else latest).
     * Returns null when Forge has no build for that Minecraft version.
     */
    fun forgeBuild(mc: String): String? =
        runCatching {
                val body =
                    httpGet("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json")
                        ?: return@runCatching null
                val promos = JSONObject(body).getJSONObject("promos")
                val recommended = promos.optString("$mc-recommended")
                val latest = promos.optString("$mc-latest")
                when {
                    recommended.isNotBlank() -> recommended
                    latest.isNotBlank() -> latest
                    else -> null
                }
            }
            .getOrNull()
}
