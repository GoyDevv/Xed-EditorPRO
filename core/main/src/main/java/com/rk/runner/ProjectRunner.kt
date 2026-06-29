package com.rk.runner

import android.app.Activity
import com.rk.exec.TerminalCommand
import com.rk.exec.isTerminalInstalled
import com.rk.exec.launchTerminal
import com.rk.file.FileObject
import com.rk.file.FileWrapper
import com.rk.file.child
import com.rk.file.localBinDir
import com.rk.projects.DetectedProjectType
import com.rk.projects.ProjectTypeDetector
import com.rk.runner.runners.web.html.HtmlRunner
import com.rk.terminal.setupAssetFile
import com.rk.terminal.setupTerminalFiles
import java.io.File

/**
 * Project-aware "Run" entry point used by the editor's play button.
 *
 * Instead of running a single file blindly, this inspects the project the file belongs to
 * ([ProjectTypeDetector]) and:
 *  - **Python / Node / Rust / Go** – runs the project from its own folder in the terminal sandbox.
 *  - **Fabric / Forge / generic Gradle** – checks the JDK and runs `./gradlew build`, surfacing any
 *    build errors in the terminal.
 *  - **Android** – exports ANDROID_HOME, runs `./gradlew assembleDebug`, then installs the built APK.
 *  - **Static web** – opens the in-app HTML preview (the existing, working web runner).
 *  - **Unknown** – not runnable; the button is hidden (no meaningful run command).
 *
 * All terminal commands run with the project root as the working directory, so relative paths,
 * `requirements.txt`, `package.json`, `gradlew`, etc. resolve correctly.
 */
object ProjectRunner {

    /** Detection is cheap but touches the filesystem; cache briefly so toolbar recomposition is fast. */
    private const val CACHE_TTL_MS = 4_000L
    private val cache = HashMap<String, Pair<Long, DetectedProjectType>>()

    /** Project types the run button supports. Only genuinely unidentifiable projects are hidden. */
    fun isRunnable(type: DetectedProjectType): Boolean = type != DetectedProjectType.UNKNOWN

    @Synchronized
    fun detect(projectRootPath: String): DetectedProjectType {
        val now = System.currentTimeMillis()
        cache[projectRootPath]?.let { (ts, type) -> if (now - ts < CACHE_TTL_MS) return type }
        val type = ProjectTypeDetector.detect(File(projectRootPath))
        cache[projectRootPath] = now to type
        return type
    }

    /** Whether the run button should be shown for the given project root. */
    fun canRun(projectRootPath: String?): Boolean {
        if (projectRootPath.isNullOrBlank()) return false
        if (!File(projectRootPath).isDirectory) return false
        return isRunnable(detect(projectRootPath))
    }

    /**
     * Resolve the project root for an editor tab: the explicit project root if present, otherwise
     * the directory containing the open file. Returns null for non-filesystem files (SAF/network),
     * which can't be run.
     */
    fun resolveProjectRootPath(projectRoot: FileObject?, file: FileObject): String? {
        if (projectRoot is FileWrapper) return projectRoot.getAbsolutePath()
        if (file is FileWrapper) return File(file.getAbsolutePath()).parent
        return null
    }

    suspend fun run(activity: Activity, projectRoot: FileObject?, file: FileObject) {
        val rootPath = resolveProjectRootPath(projectRoot, file) ?: return
        val rootFile = File(rootPath)
        val type = detect(rootPath)
        if (!isRunnable(type)) return

        // Static web is previewed in-app via the existing HTML runner.
        if (type == DetectedProjectType.WEB) {
            HtmlRunner.run(activity, webEntry(rootFile, file))
            return
        }

        // Everything else runs/builds in the sandbox, rooted at the project folder.
        // Translate shared-storage paths to /sdcard, which the sandbox binds reliably (the canonical
        // /storage/emulated/0 path is not always reachable inside proot, which caused the
        // "Cannot enter project directory" error).
        setupAssetFile("project_runner")
        // The background run path doesn't go through MkSession, so make sure the helper scripts the
        // run script sources ($LOCAL/bin/utils, etc.) are installed. Idempotent.
        setupTerminalFiles()
        val sandboxRoot = toSandboxPath(rootPath)
        val sandboxFile = toSandboxPath(file.getAbsolutePath())
        val label = "Run · ${rootFile.name}"
        val scriptPath = localBinDir().child("project_runner").absolutePath

        if (isTerminalInstalled()) {
            // Run headlessly in the background: no terminal screen is shown. Output and progress are
            // surfaced through the editor's floating build view + a notification (RunService).
            // begin() also enforces the single-build rule.
            if (!RunOutputState.begin(label = label)) return
            RunService.start(
                context = activity,
                label = label,
                workingDir = sandboxRoot,
                args = arrayListOf("/bin/bash", "-l", scriptPath, type.name, sandboxRoot, sandboxFile),
                // For Android, hand RunService the project's real path so it can locate and install
                // the built APK once the build succeeds (the Android Studio "Run" experience).
                androidApkProjectDir = if (type == DetectedProjectType.ANDROID) rootPath else null,
            )
        } else {
            // Sandbox isn't set up yet — fall back to the terminal so the rootfs can be downloaded
            // and extracted (that flow needs the terminal UI). The floating view isn't used here.
            launchTerminal(
                activity = activity,
                terminalCommand =
                    TerminalCommand(
                        sandbox = true,
                        exe = "/bin/bash",
                        args = arrayOf(scriptPath, type.name, sandboxRoot, sandboxFile),
                        id = label,
                        terminatePreviousSession = true,
                        workingDir = sandboxRoot,
                    ),
            )
        }
    }

    /**
     * Gradle dependency "sync" for Android (and other Gradle) projects — the one-time step that
     * downloads dependencies and configures the build, mirroring Android Studio's Sync. Runs in the
     * background like [run]; surfaced in the floating build view.
     */
    suspend fun sync(activity: Activity, projectRoot: FileObject?, file: FileObject) {
        val rootPath = resolveProjectRootPath(projectRoot, file) ?: return
        if (!File(rootPath).isDirectory) return
        if (!isTerminalInstalled()) return

        setupAssetFile("project_runner")
        setupTerminalFiles()
        val sandboxRoot = toSandboxPath(rootPath)
        val label = "Sync · ${File(rootPath).name}"
        val scriptPath = localBinDir().child("project_runner").absolutePath

        if (!RunOutputState.begin(label = label)) return
        RunService.start(
            context = activity,
            label = label,
            workingDir = sandboxRoot,
            // "SYNC" pseudo-type triggers the gradle dependency sync path in project_runner.sh.
            args = arrayListOf("/bin/bash", "-l", scriptPath, "SYNC", sandboxRoot, ""),
            androidApkProjectDir = null,
        )
    }

    /**
     * Map an Android path to the path the terminal sandbox can actually `cd` into. Shared storage is
     * bound at `/sdcard` inside proot, but the canonical `/storage/emulated/0` (and
     * `/storage/self/primary`) form isn't always traversable there — so rewrite those prefixes to
     * `/sdcard`. Private app paths (the sandbox home under /data) are left untouched.
     */
    fun toSandboxPath(path: String): String =
        when {
            path == "/storage/emulated/0" || path.startsWith("/storage/emulated/0/") ->
                "/sdcard" + path.removePrefix("/storage/emulated/0")
            path == "/storage/self/primary" || path.startsWith("/storage/self/primary/") ->
                "/sdcard" + path.removePrefix("/storage/self/primary")
            else -> path
        }

    /** Pick the HTML file to preview: the open file if it's HTML, else index.html, else the first HTML. */
    private fun webEntry(root: File, file: FileObject): FileObject {
        if (file is FileWrapper && file.getName().endsWith(".html", ignoreCase = true)) return file
        File(root, "index.html").takeIf { it.exists() }?.let { return FileWrapper(it) }
        root.listFiles()?.firstOrNull { it.isFile && it.extension.equals("html", ignoreCase = true) }?.let {
            return FileWrapper(it)
        }
        return file
    }
}
