package com.rk.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rk.exec.ShellUtils

/**
 * Coherent Kiro onboarding for using Kiro as the AI agent's backend.
 *
 * Kiro works with the agent **directly — no separate gateway is needed**. All this does is get you
 * signed in so [KiroAuth] can find your credentials. The flow is:
 *   1. CHECK  — is the CLI already installed, and are you already signed in?
 *   2. INSTALL — install kiro-cli in the Linux sandbox (only if it isn't already there).
 *   3. LOGIN  — `kiro-cli login` (browser device flow) or paste a token (only if not already signed in).
 *
 * Everything streams to a live log and is fully skippable if already satisfied.
 */
object KiroSetup {
    enum class Status { PENDING, RUNNING, DONE, ERROR, SKIPPED }

    val log = mutableStateListOf<String>()

    var checkStatus by mutableStateOf(Status.PENDING)
        private set
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

    /** The sign-in verification URL — only ever captured during the LOGIN step. */
    var loginUrl by mutableStateOf<String?>(null)
        private set

    /** Results of the CHECK step, for the UI. */
    var cliPresent by mutableStateOf(false)
        private set
    var loggedIn by mutableStateOf(false)
        private set
    var cliVersion by mutableStateOf<String?>(null)
        private set

    private const val MAX_LOG = 800
    private var captureLoginUrl = false

    private val pathExport = "export PATH=\"\$HOME/.local/bin:\$HOME/.kiro/bin:\$HOME/bin:\$PATH\""
    private val urlRegex = Regex("https?://[^\\s\"'<>]+")

    private fun append(line: String) {
        log.add(line)
        while (log.size > MAX_LOG) log.removeAt(0)
        // Only capture a URL while signing in, and never the installer-script URL.
        if (captureLoginUrl && loginUrl == null) {
            urlRegex.find(line)?.value?.let { url ->
                if (!url.contains("cli.kiro.dev/install") && !url.contains("/install")) loginUrl = url
            }
        }
    }

    fun fullLog(): String = log.joinToString("\n")

    fun reset() {
        log.clear()
        checkStatus = Status.PENDING
        installStatus = Status.PENDING
        loginStatus = Status.PENDING
        error = null
        loginUrl = null
        phase = ""
        cliPresent = false
        loggedIn = false
        cliVersion = null
        captureLoginUrl = false
    }

    fun kiroBin(): String = AiPrefs.kiroCliPath.ifBlank { "kiro-cli" }

    // ---------------------------------------------------------------------------------------------
    // Step 1: CHECK — does the CLI work, and are we already signed in?
    // ---------------------------------------------------------------------------------------------
    suspend fun check(): Boolean {
        running = true
        error = null
        checkStatus = Status.RUNNING
        phase = "Checking Kiro…"
        append("$ checking for kiro-cli and existing login…")

        var foundBin: String? = null
        var version: String? = null
        val probe =
            "$pathExport; " +
                "for n in kiro-cli kiro q; do " +
                "p=\"\$(command -v \$n 2>/dev/null)\"; " +
                "if [ -n \"\$p\" ]; then echo \"KIRO_BIN=\$p\"; v=\"\$(\"\$p\" --version 2>/dev/null | head -n1)\"; " +
                "[ -n \"\$v\" ] && echo \"KIRO_VER=\$v\"; break; fi; done"
        ShellUtils.runUbuntuStreaming(workingDir = null, command = listOf("bash", "-lc", probe), timeoutSeconds = 40) { line ->
            append(line)
            when {
                line.startsWith("KIRO_BIN=") -> foundBin = line.removePrefix("KIRO_BIN=").trim()
                line.startsWith("KIRO_VER=") -> version = line.removePrefix("KIRO_VER=").trim()
            }
        }

        cliPresent = !foundBin.isNullOrBlank()
        cliVersion = version
        if (cliPresent) {
            AiPrefs.kiroCliPath = foundBin!!
            append("[*] kiro-cli found: $foundBin${version?.let { " ($it)" } ?: ""}")
        } else {
            append("[*] kiro-cli not installed yet.")
        }

        KiroAuth.reset()
        loggedIn = KiroAuth.hasDiscoverableCreds()
        append(if (loggedIn) "[*] Existing Kiro login detected." else "[*] Not signed in yet.")

        checkStatus = Status.DONE
        installStatus = if (cliPresent) Status.SKIPPED else Status.PENDING
        loginStatus = if (loggedIn) Status.DONE else Status.PENDING
        phase =
            when {
                loggedIn -> "Already connected to Kiro."
                cliPresent -> "kiro-cli is installed — sign in to finish."
                else -> "kiro-cli needs to be installed."
            }
        running = false
        return loggedIn
    }

    // ---------------------------------------------------------------------------------------------
    // Step 2: INSTALL
    // ---------------------------------------------------------------------------------------------
    private val installScript =
        """
        set +e
        echo "[*] Checking download tools..."
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
        return if (!resolved.isNullOrBlank()) {
            AiPrefs.kiroCliPath = resolved!!
            cliPresent = true
            append("[*] kiro binary: $resolved")
            installStatus = Status.DONE
            phase = "kiro-cli installed."
            running = false
            true
        } else {
            installStatus = Status.ERROR
            error =
                "Could not install or locate kiro-cli (exit $code). This often means the installer has no " +
                    "binary for this device's CPU. Use \"Sign in with token\" instead (no local CLI needed). " +
                    "Copy the log for details."
            phase = "Install failed."
            running = false
            false
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Step 3: LOGIN
    // ---------------------------------------------------------------------------------------------
    suspend fun runLogin(): Boolean {
        running = true
        error = null
        loginUrl = null
        captureLoginUrl = true
        loginStatus = Status.RUNNING
        phase = "Signing in to Kiro…"
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
        captureLoginUrl = false
        KiroAuth.reset()
        loggedIn = KiroAuth.hasDiscoverableCreds()
        return if (loggedIn) {
            loginStatus = Status.DONE
            phase = "Signed in to Kiro."
            running = false
            true
        } else {
            loginStatus = Status.ERROR
            error =
                "Sign-in didn't complete (exit $code). If a link appeared above, open it and finish sign-in, " +
                    "then tap Retry. Or use \"Sign in with token\". Copy the log for details."
            phase = "Sign-in incomplete."
            running = false
            false
        }
    }

    /** Full automatic flow used by the primary button. Returns true when connected. */
    suspend fun autoConnect(): Boolean {
        if (checkStatus != Status.DONE) check()
        if (loggedIn) return true
        if (!cliPresent) {
            if (!runInstall()) return false
        }
        return runLogin()
    }

    /** Token login: store a pasted refresh token; [KiroAuth] uses it directly (no CLI needed). */
    fun useToken(token: String) {
        AiPrefs.setKey(AiProviders.KIRO.id, token.trim())
        KiroAuth.reset()
        loggedIn = token.isNotBlank() && KiroAuth.hasDiscoverableCreds()
        loginStatus = if (token.isNotBlank()) Status.DONE else Status.PENDING
        phase = if (token.isNotBlank()) "Token saved." else ""
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
