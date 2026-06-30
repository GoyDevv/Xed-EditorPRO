package com.rk.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rk.exec.ubuntuProcess
import com.rk.runner.ProjectRunner
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A single, long-lived shell the AI agent keeps **across commands and turns** (so `cd`, exported
 * variables, activated virtualenvs, etc. persist — unlike the one-shot [com.rk.exec.ShellUtils]).
 *
 * The user can [kill] it at any time (a button in the AI tab / the agent notification); the next
 * `run_command` then transparently starts a fresh shell and the model is told it was restarted.
 *
 * Implementation: one `bash` process in the Linux sandbox via [ubuntuProcess], with the shell's own
 * stderr merged into stdout (`exec 2>&1`). Each command is followed by a printed unique marker that
 * carries the exit code, so we know exactly where the command's output ends.
 *
 * State is mirrored to Compose so the UI can show "terminal running: <cmd>" + a Kill button.
 */
object AiTerminal {
    /** True while a shell process is alive. */
    var isAlive by mutableStateOf(false)
        private set

    /** The command currently executing (for the UI/notification), or null when idle. */
    var currentCommand by mutableStateOf<String?>(null)
        private set

    /** Set when the user kills the shell; cleared once the model has been informed on the next run. */
    var killedByUser by mutableStateOf(false)
        private set

    private const val MAX_OUTPUT = 60_000 // chars returned to the model
    private const val DEFAULT_TIMEOUT_MS = 600_000L

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null

    // Serializes commands so two tool calls never interleave on the same shell.
    private val mutex = Mutex()

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Start a fresh shell rooted at [workingDir] (the project dir). Returns true if it (re)started. */
    private suspend fun ensureStarted(workingDir: String): Boolean {
        if (process?.isAlive == true) return false
        return withContext(Dispatchers.IO) {
            val sandboxDir = ProjectRunner.toSandboxPath(workingDir)
            val p = ubuntuProcess(workingDir = sandboxDir, command = listOf("bash"))
            process = p
            writer = OutputStreamWriter(p.outputStream)
            reader = BufferedReader(InputStreamReader(p.inputStream))
            // Merge the shell's stderr into stdout and move to the project dir.
            writer?.apply {
                write("exec 2>&1\n")
                write("cd ${shellQuote(sandboxDir)} 2>/dev/null\n")
                flush()
            }
            isAlive = true
            true
        }
    }

    /**
     * Run [command] in the persistent shell and return its combined output + exit status. Never
     * throws. Restarts the shell automatically if it isn't running (e.g. the user killed it).
     */
    suspend fun run(command: String, workingDir: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String =
        mutex.withLock {
            runCatching {
                    val restarted = ensureStarted(workingDir)
                    val wasKilled = killedByUser
                    killedByUser = false

                    val w = writer ?: return@runCatching "Error: agent terminal unavailable."
                    val r = reader ?: return@runCatching "Error: agent terminal unavailable."

                    val marker = "__AI_END_" + UUID.randomUUID().toString().replace("-", "")
                    currentCommand = command

                    withContext(Dispatchers.IO) {
                        try {
                            w.write(command)
                            w.write("\n")
                            // Print the marker + the command's exit code once it finishes.
                            w.write("printf '%s:%s\\n' ${shellQuote(marker)} \"\$?\"\n")
                            w.flush()
                        } catch (e: Exception) {
                            markDead()
                            return@withContext "Error writing to terminal: ${e.message}"
                        }

                        val sb = StringBuilder()
                        var exit = "?"
                        val deadline = System.currentTimeMillis() + timeoutMs
                        var timedOut = false
                        while (true) {
                            if (System.currentTimeMillis() > deadline) {
                                timedOut = true
                                break
                            }
                            if (!r.ready()) {
                                delay(15)
                                continue
                            }
                            val line = r.readLine()
                            if (line == null) {
                                // Shell ended/was killed mid-command.
                                markDead()
                                break
                            }
                            val idx = line.indexOf("$marker:")
                            if (idx >= 0) {
                                if (idx > 0) sb.appendLine(line.substring(0, idx))
                                exit = line.substring(idx + marker.length + 1).trim()
                                break
                            }
                            sb.appendLine(line)
                            if (sb.length > MAX_OUTPUT) {
                                sb.append("\n…[output truncated]")
                                // Drain to the marker without keeping the rest.
                                drainToMarker(r, marker)
                                exit = "?"
                                break
                            }
                        }
                        currentCommand = null

                        if (timedOut) {
                            shutdown() // a hung command would poison the shell; restart cleanly next time
                            return@withContext buildString {
                                if (sb.isNotBlank()) appendLine(sb.toString().trimEnd())
                                append("[timed out after ${timeoutMs / 1000}s — agent terminal was restarted]")
                            }
                        }

                        buildString {
                            if (wasKilled && restarted) {
                                appendLine("[note] The agent terminal had been killed by the user; a fresh shell was started for this command.")
                            } else if (restarted) {
                                appendLine("[note] Started a new agent terminal.")
                            }
                            if (sb.isNotBlank()) appendLine(sb.toString().trimEnd())
                            append(if (isAlive) "[exit $exit]" else "[terminal ended; exit $exit]")
                        }
                    }
                }
                .getOrElse { "Error: ${it.message}" }
        }

    private fun drainToMarker(r: BufferedReader, marker: String) {
        runCatching {
            val end = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < end) {
                if (!r.ready()) continue
                val line = r.readLine() ?: break
                if (line.contains("$marker:")) break
            }
        }
    }

    private fun markDead() {
        isAlive = false
        currentCommand = null
        process = null
        writer = null
        reader = null
    }

    /** Kill the shell (user action). The next [run] starts a fresh one and informs the model. */
    fun kill() {
        if (process != null) killedByUser = true
        currentCommand = null
        runCatching { writer?.close() }
        runCatching { process?.destroyForcibly() }
        process = null
        writer = null
        reader = null
        isAlive = false
    }

    /** Full cleanup (e.g. when starting a brand-new chat); does not flag a user kill. */
    fun shutdown() {
        currentCommand = null
        runCatching { writer?.close() }
        runCatching { process?.destroyForcibly() }
        process = null
        writer = null
        reader = null
        isAlive = false
        killedByUser = false
    }
}
