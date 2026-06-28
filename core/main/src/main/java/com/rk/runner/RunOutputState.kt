package com.rk.runner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Shared, observable state for the floating build/run view shown in the editor.
 *
 * Builds/runs started by the editor's Run button execute headlessly in the sandbox via
 * [com.rk.runner.RunService] (a foreground service) — the terminal UI is never shown. The service
 * streams the process output into this object so the editor's floating view can mirror it live, and
 * posts a progress notification so the user can follow along (and stop) from outside the app.
 *
 * Only one such build runs at a time: [begin] is an atomic gate.
 */
object RunOutputState {

    /** True once a run has started; controls visibility of the floating view. */
    var isActive by mutableStateOf(false)
        private set

    /** True while the build process is still running. */
    var isRunning by mutableStateOf(false)
        private set

    /** Short label, e.g. "Run · myproject". */
    var label by mutableStateOf("")
        private set

    /** Latest non-blank output line, shown collapsed. */
    var latestLine by mutableStateOf("")
        private set

    /** Full, ANSI-stripped output, shown when expanded. */
    var output by mutableStateOf("")
        private set

    /** Process exit code once finished (null while running). */
    var exitCode by mutableStateOf<Int?>(null)
        private set

    /** Whether the floating view is expanded. Public so the editor can blur its background behind it. */
    var expanded by mutableStateOf(false)

    /** Set by the running service so [stop] can kill the underlying process. */
    private var stopper: (() -> Unit)? = null

    private val ansiRegex = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    private fun stripAnsi(text: String): String = ansiRegex.replace(text, "")

    /**
     * Begin a new run. Atomic single-build gate: returns false (and changes nothing) if a build is
     * already running.
     */
    @Synchronized
    fun begin(label: String): Boolean {
        if (isRunning) return false
        this.label = label
        latestLine = ""
        output = ""
        exitCode = null
        isRunning = true
        isActive = true
        stopper = null
        expanded = false
        return true
    }

    fun setStopper(fn: () -> Unit) {
        stopper = fn
    }

    /** Replace the output with the latest full transcript (ANSI escape codes removed). */
    fun onOutput(fullText: String) {
        val clean = stripAnsi(fullText)
        output = clean
        clean.lineSequence().lastOrNull { it.isNotBlank() }?.let { latestLine = it.trim() }
    }

    /** Mark the process finished but keep the output visible until the next run. */
    fun onFinished(exit: Int?) {
        exitCode = exit
        isRunning = false
    }

    /** Kill the running build. */
    fun stop() {
        runCatching { stopper?.invoke() }
        isRunning = false
    }

    /** Hide and clear the floating view entirely. */
    fun dismiss() {
        isActive = false
        isRunning = false
        label = ""
        latestLine = ""
        output = ""
        exitCode = null
        stopper = null
        expanded = false
    }
}
