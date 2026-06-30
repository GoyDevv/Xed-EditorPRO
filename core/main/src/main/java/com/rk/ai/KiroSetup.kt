package com.rk.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rk.exec.ShellUtils

/**
 * Automatic Kiro CLI setup: installs kiro-cli inside the Linux sandbox and logs in — with live
 * output streamed to a small view, a resolved binary path (fixing the "command not found" / PATH
 * issue), browser device-flow or token login, and copyable errors.
 *
 * All state is Compose-observable so [com.rk.ai] UI can render progress + the log.
 */
object KiroSetup {
    enum class Status { PENDING, RUNNING, DONE, ERROR }

    /** Rolling log of command output (capped). */
    val log = mutableStateListOf<String>()

    var installStatus by mutableStateOf(Status.PENDING)
        private set
    var loginStatus by mutableStateOf(Status.PENDING)
        private set
    var running by mutableStateOf(false)
        private set
    var phase by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The login verification URL (when the device/browser flow prints one). */
    var loginUrl by mutableStateOf<String?>(null)
        private set

    private const val MAX_LOG = 800

    private val pathExport = "export PATH=\"\$HOME/.local/bin:\$HOME/.kiro/bin:\$HOME/bin:\$PATH\""
    private val urlRegex = Regex("https?://[^\\s\"'<>]+")

    private fun append(line: String) {
        log.add(line)
        while (log.size > MAX_LOG) log.removeAt(0)
        // Capture the first auth URL we see.
        if (loginUrl == null) urlRegex.find(line)?.let { loginUrl = it.value }
    }

    fun fullLog(): String = log.joinToString("\n")

    /** Clears state for a fresh run. */
    fun reset() {
        log.clear()
        installStatus = Status.PENDING
        loginStatus = Status.PENDING
        error = null
        loginUrl = null
        phase = ""
    }

    /** True if a kiro binary path is already known/resolved. */
    fun kiroBin(): String = AiPrefs.kiroCliPath.ifBlank { "kiro-cli" }

    private val installScript =
        """
        set +e
        echo "[*] Checking network tools..."
        if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
          echo "[*] Installing curl via apt (this may take a moment)..."
          apt-get update -y
          apt-get install -y curl ca-certificates
        fi
        echo "[*] Downloading and running the Kiro CLI installer..."
        if command -v curl >/dev/null 2>&1; then
          curl -fsSL https://cli.kiro.dev/install | bash
        else
          wget -qO- https://cli.kiro.dev/install | bash
        fi
        echo "[*] Updating PATH for future terminal sessions..."
        if ! grep -q 'KIRO PATH (Xed)' "${'$'}HOME/.bashrc" 2>/dev/null; then
          printf '\n# KIRO PATH (Xed)\n%s\n' '$pathExport' >> "${'$'}HOME/.bashrc"
        fi
        $pathExport
        echo "[*] Locating the kiro binary..."
        for d in "${'$'}HOME/.local/bin" "${'$'}HOME/.kiro/bin" "${'$'}HOME/.kiro/cli" "${'$'}HOME/bin" "/usr/local/bin" "/usr/bin"; do
          for n in kiro-cli kiro q; do
            if [ -x "${'$'}d/${'$'}n" ]; then echo "KIRO_BIN=${'$'}d/${'$'}n"; fi
          done
        done
        for n in kiro-cli kiro q; do p="${'$'}(command -v ${'$'}n 2>/dev/null)"; [ -n "${'$'}p" ] && echo "KIRO_BIN=${'$'}p"; done
        echo "[*] INSTALL_DONE"
        """.trimIndent()

    /** Install kiro-cli. Returns true on success and persists the resolved binary path. */
    suspend fun runInstall(): Boolean {
        running = true
        error = null
        installStatus = Status.RUNNING
        phase = "Installing kiro-cli…"
        append("$ curl -fsSL https://cli.kiro.dev/install | bash")
        var resolved: String? = null
        val code =
            ShellUtils.runUbuntuStreaming(
                workingDir = null,
                command = listOf("bash", "-lc", installScript),
                timeoutSeconds = 900,
            ) { line ->
                append(line)
                if (line.startsWith("KIRO_BIN=")) resolved = line.removePrefix("KIRO_BIN=").trim()
            }
        if (resolved.isNullOrBlank()) {
            // Fall back to a direct probe in case streaming missed it.
            resolved = probeBinary()
        }
        return if (!resolved.isNullOrBlank()) {
            AiPrefs.kiroCliPath = resolved!!
            append("[*] kiro binary: $resolved")
            installStatus = Status.DONE
            phase = "kiro-cli installed."
            running = false
            true
        } else {
            installStatus = Status.ERROR
            error = "Could not install or locate kiro-cli (exit $code). See the log above; you can copy it for details."
            phase = "Install failed."
            running = false
            false
        }
    }

    private suspend fun probeBinary(): String? {
        var found: String? = null
        ShellUtils.runUbuntuStreaming(
            workingDir = null,
            command =
                listOf(
                    "bash",
                    "-lc",
                    "$pathExport; for n in kiro-cli kiro q; do p=\"\$(command -v \$n 2>/dev/null)\"; [ -n \"\$p\" ] && echo \"KIRO_BIN=\$p\"; done",
                ),
            timeoutSeconds = 30,
        ) { line ->
            if (line.startsWith("KIRO_BIN=")) found = line.removePrefix("KIRO_BIN=").trim()
        }
        return found
    }

    /**
     * Run `kiro-cli login` (browser device flow). Streams output; the verification URL is captured in
     * [loginUrl]. Completes when the login process exits. Returns true if a Kiro login is then found.
     */
    suspend fun runLogin(): Boolean {
        running = true
        error = null
        loginUrl = null
        loginStatus = Status.RUNNING
        phase = "Logging in to Kiro…"
        val bin = kiroBin()
        append("$ $bin login")
        val script = "$pathExport; ${shellQuote(bin)} login; echo \"[*] LOGIN_DONE exit=\$?\""
        val code =
            ShellUtils.runUbuntuStreaming(
                workingDir = null,
                command = listOf("bash", "-lc", script),
                timeoutSeconds = 300,
            ) { line ->
                append(line)
            }
        // Login writes credentials to the sandbox; let KiroAuth re-discover them.
        KiroAuth.reset()
        return if (KiroAuth.hasDiscoverableCreds()) {
            loginStatus = Status.DONE
            phase = "Logged in to Kiro."
            running = false
            true
        } else {
            loginStatus = Status.ERROR
            error =
                "Login did not complete (exit $code). If a browser link appeared, open it and finish sign-in, " +
                    "then tap Retry. You can also use token login. Copy the log for details."
            phase = "Login incomplete."
            running = false
            false
        }
    }

    /** Token login: store a pasted refresh token; KiroAuth will use it directly. */
    fun useToken(token: String) {
        AiPrefs.setKey(AiProviders.KIRO.id, token.trim())
        KiroAuth.reset()
        loginStatus = if (token.isNotBlank()) Status.DONE else Status.PENDING
        phase = if (token.isNotBlank()) "Token saved." else ""
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
