package com.rk.ai

import android.database.sqlite.SQLiteDatabase
import com.rk.file.sandboxHomeDir
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Native Kiro authentication — the "automatic login".
 *
 * Kiro's `ksk_…` key isn't a public REST API; instead Kiro IDE / kiro-cli log in (Builder ID / social
 * / AWS SSO) and cache an access+refresh token. This class auto-discovers those cached credentials
 * from the app's Linux sandbox (where kiro-cli runs) and refreshes the access token automatically, so
 * the agent can talk to Kiro with no manual token pasting and no external gateway process.
 *
 * Credential sources (first found wins), all inside the sandbox home:
 *  - kiro-cli SQLite DB:  ~/.local/share/kiro-cli/data.sqlite3  (table auth_kv)
 *  - amazon-q-cli DB:     ~/.local/share/amazon-q/data.sqlite3
 *  - Kiro IDE JSON cache: ~/.aws/sso/cache/kiro-auth-token.json
 *  - a refresh token the user pasted in settings (AiPrefs).
 *
 * Refresh:
 *  - Kiro Desktop (no clientId/secret): POST https://prod.{region}.auth.desktop.kiro.dev/refreshToken
 *  - AWS SSO OIDC (clientId+secret):    POST https://oidc.{region}.amazonaws.com/token
 *
 * Ported from the open-source kiro-gateway (jwadow/kiro-gateway, AGPL) protocol description.
 */
object KiroAuth {

    data class Creds(
        var accessToken: String? = null,
        var refreshToken: String? = null,
        var profileArn: String? = null,
        var region: String = "us-east-1",
        var ssoRegion: String? = null,
        var clientId: String? = null,
        var clientSecret: String? = null,
        var expiresAtEpochMs: Long = 0L,
        var source: String = "none",
    ) {
        val isSsoOidc: Boolean
            get() = !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()
    }

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    @Volatile private var cached: Creds? = null

    private fun home(): File = sandboxHomeDir()

    /** Candidate kiro-cli / amazon-q SQLite databases. */
    private fun sqliteCandidates(): List<File> =
        listOf(
            File(home(), ".local/share/kiro-cli/data.sqlite3"),
            File(home(), ".local/share/amazon-q/data.sqlite3"),
        )

    private fun jsonCacheDir(): File = File(home(), ".aws/sso/cache")

    private val SQLITE_TOKEN_KEYS =
        listOf("kirocli:social:token", "kirocli:odic:token", "codewhisperer:odic:token")
    private val SQLITE_REG_KEYS =
        listOf("kirocli:odic:device-registration", "codewhisperer:odic:device-registration")

    /** True if any credential source is present (used to decide whether Kiro is "logged in"). */
    fun hasDiscoverableCreds(): Boolean =
        sqliteCandidates().any { it.exists() } ||
            (jsonCacheDir().exists() && jsonCacheDir().listFiles()?.any { it.name.endsWith(".json") } == true) ||
            AiPrefs.getKey(AiProviders.KIRO.id).isNotBlank()

    /** Discover credentials from the sandbox (SQLite first, then JSON cache, then pasted refresh token). */
    fun discover(): Creds? {
        loadFromSqlite()?.let { return it }
        loadFromJsonCache()?.let { return it }
        val pasted = AiPrefs.getKey(AiProviders.KIRO.id) // user may paste a raw refresh token as the "key"
        if (pasted.isNotBlank()) {
            return Creds(refreshToken = pasted, source = "pasted")
        }
        return null
    }

    private fun loadFromSqlite(): Creds? {
        for (db in sqliteCandidates()) {
            if (!db.exists()) continue
            val c = runCatching { readSqlite(db) }.getOrNull()
            if (c?.refreshToken?.isNotBlank() == true) return c
        }
        return null
    }

    private fun readSqlite(db: File): Creds? {
        var sql: SQLiteDatabase? = null
        return try {
            sql = SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val creds = Creds(source = "sqlite:${db.name}")
            var tokenJson: String? = null
            for (key in SQLITE_TOKEN_KEYS) {
                tokenJson = queryValue(sql, key)
                if (tokenJson != null) break
            }
            if (tokenJson == null) return null
            JSONObject(tokenJson).let { o ->
                creds.accessToken = o.optString("access_token", "").ifBlank { null }
                creds.refreshToken = o.optString("refresh_token", "").ifBlank { null }
                creds.profileArn = o.optString("profile_arn", "").ifBlank { null }
                o.optString("region", "").ifBlank { null }?.let { creds.ssoRegion = it }
                o.optString("expires_at", "").ifBlank { null }?.let { creds.expiresAtEpochMs = parseIso(it) }
            }
            var regJson: String? = null
            for (key in SQLITE_REG_KEYS) {
                regJson = queryValue(sql, key)
                if (regJson != null) break
            }
            regJson?.let {
                JSONObject(it).let { o ->
                    creds.clientId = o.optString("client_id", "").ifBlank { null }
                    creds.clientSecret = o.optString("client_secret", "").ifBlank { null }
                    if (creds.ssoRegion == null) creds.ssoRegion = o.optString("region", "").ifBlank { null }
                }
            }
            if (creds.refreshToken.isNullOrBlank()) null else creds
        } catch (e: Exception) {
            null
        } finally {
            runCatching { sql?.close() }
        }
    }

    private fun queryValue(db: SQLiteDatabase, key: String): String? {
        return runCatching {
                db.rawQuery("SELECT value FROM auth_kv WHERE key = ? LIMIT 1", arrayOf(key)).use { cur ->
                    if (cur.moveToFirst()) cur.getString(0) else null
                }
            }
            .getOrNull()
    }

    private fun loadFromJsonCache(): Creds? {
        val dir = jsonCacheDir()
        if (!dir.isDirectory) return null
        val primary = File(dir, "kiro-auth-token.json")
        val file =
            if (primary.exists()) primary
            else dir.listFiles()?.firstOrNull { it.name.endsWith(".json") } ?: return null
        return runCatching {
                val o = JSONObject(file.readText())
                val creds =
                    Creds(
                        accessToken = o.optString("accessToken", "").ifBlank { null },
                        refreshToken = o.optString("refreshToken", "").ifBlank { null },
                        profileArn = o.optString("profileArn", "").ifBlank { null },
                        region = o.optString("region", "us-east-1").ifBlank { "us-east-1" },
                        clientId = o.optString("clientId", "").ifBlank { null },
                        clientSecret = o.optString("clientSecret", "").ifBlank { null },
                        source = "json:${file.name}",
                    )
                o.optString("expiresAt", "").ifBlank { null }?.let { creds.expiresAtEpochMs = parseIso(it) }
                // Enterprise: clientIdHash → device-registration file holds clientId/secret.
                o.optString("clientIdHash", "").ifBlank { null }?.let { hash ->
                    val reg = File(dir, "$hash.json")
                    if (reg.exists()) {
                        runCatching {
                            val r = JSONObject(reg.readText())
                            creds.clientId = r.optString("clientId", "").ifBlank { creds.clientId }
                            creds.clientSecret = r.optString("clientSecret", "").ifBlank { creds.clientSecret }
                        }
                    }
                }
                if (creds.refreshToken.isNullOrBlank() && creds.accessToken.isNullOrBlank()) null else creds
            }
            .getOrNull()
    }

    private fun parseIso(s: String): Long =
        runCatching {
                val norm = s.replace("Z", "+0000")
                val formats = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ")
                for (f in formats) {
                    val t =
                        runCatching {
                                java.text.SimpleDateFormat(f, java.util.Locale.US).parse(norm)?.time
                            }
                            .getOrNull()
                    if (t != null) return@runCatching t
                }
                0L
            }
            .getOrDefault(0L)

    private fun expiringSoon(c: Creds): Boolean {
        if (c.expiresAtEpochMs <= 0L) return true
        return System.currentTimeMillis() + 600_000L >= c.expiresAtEpochMs
    }

    /** Returns a valid access token, discovering + refreshing as needed. Throws with a clear message. */
    suspend fun getAccessToken(): String =
        withContext(Dispatchers.IO) {
            val c = cached ?: discover() ?: throw IOException(
                "No Kiro login found. Log in with kiro-cli in the terminal (kiro-cli login) or paste a refresh token in AI settings."
            )
            cached = c
            if (!c.accessToken.isNullOrBlank() && !expiringSoon(c)) return@withContext c.accessToken!!
            refresh(c)
            c.accessToken ?: throw IOException("Failed to obtain Kiro access token.")
        }

    fun currentProfileArn(): String? = cached?.profileArn
    fun currentRegion(): String = cached?.region ?: "us-east-1"
    fun apiHost(): String = "https://codewhisperer.${currentRegion()}.amazonaws.com"

    /** Force a refresh (used on 403). */
    suspend fun forceRefresh(): String =
        withContext(Dispatchers.IO) {
            val c = cached ?: discover() ?: throw IOException("No Kiro credentials.")
            cached = c
            refresh(c)
            c.accessToken ?: throw IOException("Kiro token refresh failed.")
        }

    private fun refresh(c: Creds) {
        if (c.isSsoOidc) refreshOidc(c) else refreshDesktop(c)
    }

    private fun refreshDesktop(c: Creds) {
        val rt = c.refreshToken ?: throw IOException("No Kiro refresh token.")
        val url = "https://prod.${c.region}.auth.desktop.kiro.dev/refreshToken"
        val body = JSONObject().put("refreshToken", rt).toString().toRequestBody(JSON)
        val req =
            Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "KiroIDE")
                .post(body)
                .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw IOException("Kiro token refresh failed (${resp.code}): ${text.take(300)}")
            val o = JSONObject(text)
            c.accessToken = o.optString("accessToken", "").ifBlank { c.accessToken }
            o.optString("refreshToken", "").ifBlank { null }?.let { c.refreshToken = it }
            o.optString("profileArn", "").ifBlank { null }?.let { c.profileArn = it }
            val expiresIn = o.optInt("expiresIn", 3600)
            c.expiresAtEpochMs = System.currentTimeMillis() + (expiresIn - 60) * 1000L
        }
    }

    private fun refreshOidc(c: Creds) {
        val region = c.ssoRegion ?: c.region
        val url = "https://oidc.$region.amazonaws.com/token"
        val payload =
            JSONObject()
                .put("grantType", "refresh_token")
                .put("clientId", c.clientId)
                .put("clientSecret", c.clientSecret)
                .put("refreshToken", c.refreshToken)
                .toString()
                .toRequestBody(JSON)
        val req = Request.Builder().url(url).addHeader("Content-Type", "application/json").post(payload).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) throw IOException("AWS SSO OIDC refresh failed (${resp.code}): ${text.take(300)}")
            val o = JSONObject(text)
            c.accessToken = o.optString("accessToken", "").ifBlank { c.accessToken }
            o.optString("refreshToken", "").ifBlank { null }?.let { c.refreshToken = it }
            val expiresIn = o.optInt("expiresIn", 3600)
            c.expiresAtEpochMs = System.currentTimeMillis() + (expiresIn - 60) * 1000L
        }
    }

    /** Drop the in-memory credentials (e.g. after the user re-logs in). */
    fun reset() {
        cached = null
    }
}
