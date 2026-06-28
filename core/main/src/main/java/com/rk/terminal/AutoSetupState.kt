package com.rk.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import java.lang.ref.WeakReference

enum class AutoSetupPhase {
    Idle,
    Running,
    Done,
    Error,
}

/**
 * Observable state for the first-launch Auto Setup flow.
 *
 * Auto Setup runs `auto_setup.sh` inside the terminal sandbox (apt update/upgrade + install of the
 * core tools). The script prints machine-readable markers that this object parses into a clean
 * progress UI shown by [com.rk.terminal.AutoSetupOverlay], while the terminal itself runs behind it:
 *
 *   __XEDPROGRESS__ <percent> <message>   → progress + status line
 *   __XEDDONE__                            → finished successfully
 *   __XEDERROR__ <message>                 → failed (message optional)
 *
 * Wiring mirrors [com.rk.runner.RunOutputState]: [AutoSetup] calls [begin] before launching the
 * terminal, [TerminalScreen] calls [attach] when the matching session is created, and
 * [TerminalBackEnd] calls [onOutput]/[onFinished].
 */
object AutoSetupState {

    const val SESSION_ID = "Xed Auto Setup"

    private const val MARKER_PROGRESS = "__XEDPROGRESS__"
    private const val MARKER_DONE = "__XEDDONE__"
    private const val MARKER_ERROR = "__XEDERROR__"

    var phase by mutableStateOf(AutoSetupPhase.Idle)
        private set

    /** 0f..1f progress for the bar. */
    var progress by mutableStateOf(0f)
        private set

    /** Human-readable current step, e.g. "Installing tools (curl, git, wget)". */
    var status by mutableStateOf("")
        private set

    /** Full terminal transcript, used for the error report. */
    var log by mutableStateOf("")
        private set

    var expectedSessionId: String? = null
        private set

    private var trackedSession: WeakReference<TerminalSession>? = null

    val isActive: Boolean
        get() = phase != AutoSetupPhase.Idle

    fun begin(sessionId: String) {
        phase = AutoSetupPhase.Running
        progress = 0f
        status = ""
        log = ""
        expectedSessionId = sessionId
        trackedSession = null
    }

    fun attach(session: TerminalSession) {
        trackedSession = WeakReference(session)
        runCatching { session.emulator?.screen?.transcriptTextWithoutJoinedLines }.getOrNull()?.let { onOutput(it) }
    }

    fun isTracked(session: TerminalSession): Boolean = trackedSession?.get() === session

    fun onOutput(transcript: String) {
        log = transcript

        // Latest progress marker wins.
        transcript
            .lineSequence()
            .lastOrNull { it.contains(MARKER_PROGRESS) }
            ?.let { line ->
                val rest = line.substringAfter(MARKER_PROGRESS).trim()
                val pct = rest.substringBefore(' ').toIntOrNull()
                if (pct != null) progress = (pct / 100f).coerceIn(0f, 1f)
                val msg = rest.substringAfter(' ', "").trim()
                if (msg.isNotEmpty()) status = msg
            }

        when {
            transcript.contains(MARKER_DONE) -> {
                progress = 1f
                phase = AutoSetupPhase.Done
            }
            transcript.contains(MARKER_ERROR) -> {
                phase = AutoSetupPhase.Error
            }
        }
    }

    /** Called when the terminal process exits; infer success/failure if no explicit marker arrived. */
    fun onFinished() {
        if (phase == AutoSetupPhase.Running) {
            phase = if (log.contains(MARKER_DONE)) AutoSetupPhase.Done else AutoSetupPhase.Error
        }
    }

    fun stop() {
        trackedSession?.get()?.finishIfRunning()
    }

    fun reset() {
        phase = AutoSetupPhase.Idle
        progress = 0f
        status = ""
        log = ""
        expectedSessionId = null
        trackedSession = null
    }
}
