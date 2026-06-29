package com.rk.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import java.lang.ref.WeakReference

enum class AutoSetupPhase {
    Idle,
    Preparing,
    Downloading,
    Extracting,
    Installing,
    Done,
    Error,
}

/**
 * Observable state for the first-launch Auto Setup flow, modelled as ordered phases so the
 * full-screen progress UI can show real, end-to-end progress:
 *
 *   Preparing → Downloading (sandbox rootfs) → Extracting → Installing (apt packages) → Done/Error
 *
 * Progress is a single 0..1 value spanning all phases (download 0–40%, extraction ~46%, install
 * 50–100%) so it never looks "stuck at 0%" during the long sandbox download. A live tail of the
 * terminal output is exposed for the on-screen log.
 *
 * Fed by [com.rk.activities.terminal.Terminal] (download/extraction) and [TerminalBackEnd]
 * (install output markers from auto_setup.sh).
 */
object AutoSetupState {

    const val SESSION_ID = "Xed Auto Setup"

    private const val MARKER_PROGRESS = "__XEDPROGRESS__"
    private const val MARKER_DONE = "__XEDDONE__"
    private const val MARKER_ERROR = "__XEDERROR__"

    private const val DOWNLOAD_WEIGHT = 0.40f
    private const val EXTRACT_AT = 0.46f
    private const val INSTALL_BASE = 0.50f

    var phase by mutableStateOf(AutoSetupPhase.Idle)
        private set

    /** Overall progress 0f..1f across all phases. */
    var progress by mutableStateOf(0f)
        private set

    /** Current human-readable step, e.g. "Downloading sandbox (32/55 MB)" or "Installing Node.js". */
    var status by mutableStateOf("")
        private set

    /** Full terminal transcript (for the live output tail + error report). */
    var log by mutableStateOf("")
        private set

    var expectedSessionId: String? = null
        private set

    private var trackedSession: WeakReference<TerminalSession>? = null

    val isActive: Boolean
        get() = phase != AutoSetupPhase.Idle

    private fun mb(bytes: Long): String = "%.0f".format(bytes / (1024.0 * 1024.0))

    fun begin(sessionId: String) {
        phase = AutoSetupPhase.Preparing
        progress = 0f
        status = "Preparing…"
        log = ""
        expectedSessionId = sessionId
        trackedSession = null
    }

    /** Sandbox rootfs download progress (called from the terminal download flow). */
    fun onDownload(downloadedBytes: Long, totalBytes: Long) {
        if (phase == AutoSetupPhase.Done || phase == AutoSetupPhase.Error) return
        phase = AutoSetupPhase.Downloading
        progress = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes) * DOWNLOAD_WEIGHT else 0f
        status =
            if (totalBytes > 0) "Downloading sandbox (${mb(downloadedBytes)}/${mb(totalBytes)} MB)"
            else "Downloading sandbox…"
    }

    /** Download finished; sandbox is being extracted / prepared. */
    fun onExtracting() {
        if (phase == AutoSetupPhase.Done || phase == AutoSetupPhase.Error) return
        phase = AutoSetupPhase.Extracting
        progress = EXTRACT_AT
        status = "Extracting & preparing sandbox…"
    }

    fun attach(session: TerminalSession) {
        trackedSession = WeakReference(session)
        if (phase == AutoSetupPhase.Preparing || phase == AutoSetupPhase.Downloading) {
            onExtracting()
        }
        runCatching { session.emulator?.screen?.transcriptTextWithoutJoinedLines }.getOrNull()?.let { onOutput(it) }
    }

    fun isTracked(session: TerminalSession): Boolean = trackedSession?.get() === session

    fun onOutput(transcript: String) {
        log = transcript

        transcript
            .lineSequence()
            .lastOrNull { it.contains(MARKER_PROGRESS) }
            ?.let { line ->
                val rest = line.substringAfter(MARKER_PROGRESS).trim()
                val pct = rest.substringBefore(' ').toIntOrNull()
                if (pct != null) {
                    phase = AutoSetupPhase.Installing
                    progress = (INSTALL_BASE + (pct / 100f) * (1f - INSTALL_BASE)).coerceIn(INSTALL_BASE, 1f)
                }
                val msg = rest.substringAfter(' ', "").trim()
                if (msg.isNotEmpty()) status = msg
            }

        when {
            transcript.contains(MARKER_DONE) -> {
                progress = 1f
                phase = AutoSetupPhase.Done
                status = "Setup complete"
            }
            transcript.contains(MARKER_ERROR) -> {
                phase = AutoSetupPhase.Error
                status = "Setup failed"
            }
        }
    }

    /** Process exited; infer success/failure if no explicit marker arrived. */
    fun onFinished() {
        if (phase == AutoSetupPhase.Done || phase == AutoSetupPhase.Error) return
        phase = if (log.contains(MARKER_DONE)) AutoSetupPhase.Done else AutoSetupPhase.Error
        status = if (phase == AutoSetupPhase.Done) "Setup complete" else "Setup failed"
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
