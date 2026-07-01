package com.rk.git

import com.rk.exec.ShellUtils
import com.rk.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal GitHub access using the token saved in Git settings (username + personal access token).
 *
 * Each call is a short-lived `curl` command run in the Linux sandbox with a hard timeout (so the
 * process is auto-killed after a few seconds — the "run a script, kill it, toast when done" approach).
 * This avoids a heavy native API client while still doing everything from the user's own token.
 */
object GitHubCli {
    data class Result(val ok: Boolean, val code: Int, val body: String)

    data class Repo(val fullName: String, val cloneUrl: String)

    data class Run(
        val title: String,
        val status: String,
        val conclusion: String,
        val htmlUrl: String,
        val branch: String,
    )

    fun hasToken(): Boolean = Settings.git_password.isNotBlank()

    private fun sq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** owner/repo parsed from a github remote URL (ssh or https), or null. */
    fun parseOwnerRepo(remote: String?): Pair<String, String>? {
        if (remote.isNullOrBlank()) return null
        val m = Regex("github\\.com[:/]+([^/]+)/([^/.\\s]+)").find(remote) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    private suspend fun api(method: String, path: String, jsonBody: String? = null): Result =
        withContext(Dispatchers.IO) {
            val token = Settings.git_password
            if (token.isBlank()) {
                return@withContext Result(
                    false,
                    -1,
                    "No GitHub token set. Add it in Git settings (the password field = a personal access token).",
                )
            }
            val script = buildString {
                append("command -v curl >/dev/null 2>&1 || { apt-get update -y >/dev/null 2>&1; apt-get install -y curl >/dev/null 2>&1; }; ")
                append("curl -sS -m 15 -w '\\n%{http_code}' -X ").append(method).append(' ')
                append("-H ").append(sq("Authorization: Bearer $token")).append(' ')
                append("-H ").append(sq("Accept: application/vnd.github+json")).append(' ')
                append("-H ").append(sq("X-GitHub-Api-Version: 2022-11-28")).append(' ')
                if (jsonBody != null) {
                    append("-H ").append(sq("Content-Type: application/json")).append(" --data ").append(sq(jsonBody)).append(' ')
                }
                append(sq("https://api.github.com$path"))
            }
            val res = ShellUtils.runUbuntu(command = arrayOf("bash", "-lc", script), timeoutSeconds = 25L)
            val stdout = res.output.trim()
            if (stdout.isBlank()) {
                return@withContext Result(false, res.exitCode, res.error.ifBlank { "No response (is curl installed / is there network?)" })
            }
            val lines = stdout.split("\n")
            val code = lines.last().trim().toIntOrNull() ?: -1
            val body = if (lines.size > 1) lines.dropLast(1).joinToString("\n") else ""
            Result(code in 200..299, code, body)
        }

    private fun errorMessage(result: Result): String =
        runCatching { JSONObject(result.body).optString("message").ifBlank { "HTTP ${result.code}" } }
            .getOrDefault("HTTP ${result.code}")

    /** Create a repository under the authenticated user. Returns the clone URL on success. */
    suspend fun createRepo(name: String, private: Boolean): kotlin.Result<String> {
        val res = api("POST", "/user/repos", JSONObject().put("name", name).put("private", private).toString())
        if (!res.ok) return kotlin.Result.failure(Exception(errorMessage(res)))
        val cloneUrl = runCatching { JSONObject(res.body).optString("clone_url") }.getOrNull()
        return if (cloneUrl.isNullOrBlank()) kotlin.Result.failure(Exception("No clone URL in response"))
        else kotlin.Result.success(cloneUrl)
    }

    /** List the authenticated user's repositories (most-recently-updated first). */
    suspend fun listRepos(): kotlin.Result<List<Repo>> {
        val res = api("GET", "/user/repos?per_page=100&sort=updated")
        if (!res.ok) return kotlin.Result.failure(Exception(errorMessage(res)))
        return runCatching {
            val arr = JSONArray(res.body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Repo(o.optString("full_name"), o.optString("clone_url"))
            }
        }
    }

    /** Open a pull request. */
    suspend fun createPr(owner: String, repo: String, head: String, base: String, title: String, body: String): kotlin.Result<String> {
        val payload =
            JSONObject().put("title", title).put("head", head).put("base", base).put("body", body).toString()
        val res = api("POST", "/repos/$owner/$repo/pulls", payload)
        if (!res.ok) return kotlin.Result.failure(Exception(errorMessage(res)))
        val url = runCatching { JSONObject(res.body).optString("html_url") }.getOrNull()
        return kotlin.Result.success(url ?: "Pull request created")
    }

    /** Recent workflow runs for the repo. */
    suspend fun listRuns(owner: String, repo: String): kotlin.Result<List<Run>> {
        val res = api("GET", "/repos/$owner/$repo/actions/runs?per_page=20")
        if (!res.ok) return kotlin.Result.failure(Exception(errorMessage(res)))
        return runCatching {
            val runs = JSONObject(res.body).optJSONArray("workflow_runs") ?: JSONArray()
            (0 until runs.length()).mapNotNull { i ->
                val o = runs.optJSONObject(i) ?: return@mapNotNull null
                Run(
                    title = o.optString("display_title").ifBlank { o.optString("name") },
                    status = o.optString("status"),
                    conclusion = o.optString("conclusion"),
                    htmlUrl = o.optString("html_url"),
                    branch = o.optString("head_branch"),
                )
            }
        }
    }
}
