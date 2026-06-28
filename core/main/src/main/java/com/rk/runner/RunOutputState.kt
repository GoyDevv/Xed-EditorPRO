package com.rk.runner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import java.lang.ref.WeakReference

/**
 * Shared, observable state for the floating build/run progress view shown in the editor.
 *
 * The editor's Run button launches a build/run inside the terminal sandbox. That terminal session
 * keeps running even after the user navigates back to the editor, so this singleton mirrors the
 * live output of the session into Compose state that the editor can observe without being on the
 * terminal screen.
 *
 * Wiring:
 *  - [com.rk.runner.ProjectRunner] calls [begin] right before launching the terminal.
 *  - [com.rk.terminal.TerminalScreen] calls [attach] once the matching session is created.
 *  - [com.rk.terminal.TerminalBackEnd] calls [onOutput] on every screen update and [onFinished]
 *    when the process ends.
 *  - [RunOutputView] renders the state and calls [stop] / [dismiss].
 */
object RunOutputState {

    /** True once a run/build has been started; controls visibility of the floating view. */
    var isActive by mutableStateOf(false)
        private set

    /** True while the underlying process is still running. */
    var isRunning by mutableStateOf(false)
        private set

    /** Short human label for the current run, e.g. "Run · myproject". */
    var label by mutableStateOf("")
        private set

    /** Latest non-blank output line, shown when the view is collapsed. */
    var latestLine by mutableStateOf("")
        private set

    /** Full live output transcript, shown when the view is expanded. */
    var output by mutableStateOf("")
        private set

    /** Id of the session we expect to track; matched at session creation time. */
    var expectedSessionId: String? = null
        private set

    private var trackedSession: WeakReference<TerminalSession>? = null

    /**
     * Begin tracking a new run. Clears any previous output and shows the view. Called before the
     * terminal is launched, so [sessionId] is matched against the session created afterwards.
     *
     * Acts as an atomic single-build gate: returns `false` (and changes nothing) if a build is
     * already running, so callers must not launch a terminal in that case.
     */
    @Synchronized
    fun begin(label: String, sessionId: String): Boolean {
        if (isRunning) return false
        this.label = label
        expectedSessionId = sessionId
        latestLine = ""
        output = ""
        isRunning = true
        isActive = true
        trackedSession = null
        return true
    }

    /** Attach the live terminal session once it has been created by the terminal screen. */
    fun attach(session: TerminalSession) {
        trackedSession = WeakReference(session)
        // Seed with whatever is already on screen.
        runCatching { session.emulator?.screen?.transcriptTextWithoutJoinedLines }
            .getOrNull()
            ?.let { onOutput(it) }
    }

    /** Whether [session] is the run session currently being tracked. */
    fun isTracked(session: TerminalSession): Boolean = trackedSession?.get() === session

    /** Update output from the latest terminal transcript text. */
    fun onOutput(transcript: String) {
        output = transcript
        transcript.lineSequence().lastOrNull { it.isNotBlank() }?.let { latestLine = it.trim() }
    }

    /** Mark the process as finished but keep the output visible until the next run. */
    fun onFinished() {
        isRunning = false
    }

    /** Force-stop the running session. */
    fun stop() {
        trackedSession?.get()?.finishIfRunning()
        isRunning = false
    }

    /** Hide and clear the floating view entirely. */
    fun dismiss() {
        isActive = false
        isRunning = false
        latestLine = ""
        output = ""
        label = ""
        expectedSessionId = null
        trackedSession = null
    }
}
