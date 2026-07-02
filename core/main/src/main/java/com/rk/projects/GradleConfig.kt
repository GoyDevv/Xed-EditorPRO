package com.rk.projects

import com.rk.settings.Preference
import java.io.File

/**
 * Per-project Gradle build options, edited in the IDE Configuration view and applied by the Run
 * button (see [com.rk.runner.ProjectRunner] → `project_runner.sh`).
 *
 * Three independent settings, all scoped to a single project (keyed by its absolute path):
 *  - **Build type** – Debug or Release. For Android this picks `assembleDebug` / `assembleRelease`.
 *  - **Log level** – a single, mutually-exclusive Gradle verbosity flag (`--info` by default).
 *  - **Additional flags** – any number of extra Gradle flags the user toggles on.
 *
 * Values are stored in the shared [Preference] store, so they survive restarts and are restored the
 * next time the same project is opened.
 */
object GradleConfig {

    /** A single, optional Gradle flag the user can toggle on for a project. */
    data class Flag(val arg: String, val label: String, val description: String)

    /** The catalog of extra flags offered in the UI (kept in a stable, predictable order). */
    val ADDITIONAL_FLAGS: List<Flag> =
        listOf(
            Flag("--stacktrace", "Stacktrace", "Print a full stacktrace when the build fails."),
            Flag("--offline", "Offline", "Build using only cached dependencies (no network access)."),
            Flag("--refresh-dependencies", "Refresh dependencies", "Re-download and re-validate every dependency."),
            Flag("--rerun-tasks", "Rerun tasks", "Ignore up-to-date checks and rerun every task."),
            Flag("--no-build-cache", "No build cache", "Do not read from or write to the build cache."),
            Flag("--build-cache", "Build cache", "Reuse cached task outputs when possible."),
            Flag("--parallel", "Parallel", "Build independent modules in parallel."),
            Flag("--continue", "Continue on failure", "Keep building other tasks after one fails."),
            Flag("--no-daemon", "No daemon", "Run the build without the long-lived Gradle daemon."),
            Flag("--warning-mode=all", "All warnings", "Show every warning, including deprecations."),
        )

    /** Mutually-exclusive Gradle verbosity. Empty [arg] means Gradle's normal (lifecycle) output. */
    enum class LogLevel(val arg: String, val label: String, val description: String) {
        QUIET("--quiet", "Quiet", "Errors only."),
        WARN("--warn", "Warn", "Warnings and errors."),
        LIFECYCLE("", "Lifecycle", "Gradle's normal output."),
        INFO("--info", "Info", "Verbose, informative output."),
        DEBUG("--debug", "Debug", "Everything — for deep troubleshooting."),
    }

    /** Debug vs Release. Drives the Android assemble task; informational for other Gradle builds. */
    enum class BuildType(val id: String, val label: String, val description: String) {
        DEBUG("debug", "Debug", "Fast, debuggable build (Android: assembleDebug)."),
        RELEASE("release", "Release", "Optimized, shippable build (Android: assembleRelease)."),
    }

    val DEFAULT_LOG_LEVEL = LogLevel.INFO
    val DEFAULT_BUILD_TYPE = BuildType.DEBUG

    private fun keyFlags(root: File) = "gradle_flags::${root.absolutePath}"

    private fun keyLog(root: File) = "gradle_loglevel::${root.absolutePath}"

    private fun keyBuild(root: File) = "gradle_buildtype::${root.absolutePath}"

    // ---- Additional flags -----------------------------------------------------------------------

    /** The set of currently-enabled flag args for [root]. */
    fun selectedFlags(root: File): Set<String> {
        val raw = Preference.getString(keyFlags(root), "")
        if (raw.isBlank()) return emptySet()
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun isFlagEnabled(root: File, arg: String): Boolean = selectedFlags(root).contains(arg)

    fun setFlag(root: File, arg: String, enabled: Boolean) {
        val set = selectedFlags(root).toMutableSet()
        if (enabled) set.add(arg) else set.remove(arg)
        Preference.setString(keyFlags(root), set.joinToString("\n"))
    }

    // ---- Log level ------------------------------------------------------------------------------

    fun logLevel(root: File): LogLevel =
        runCatching { LogLevel.valueOf(Preference.getString(keyLog(root), DEFAULT_LOG_LEVEL.name)) }
            .getOrDefault(DEFAULT_LOG_LEVEL)

    fun setLogLevel(root: File, level: LogLevel) = Preference.setString(keyLog(root), level.name)

    // ---- Build type -----------------------------------------------------------------------------

    fun buildType(root: File): BuildType =
        runCatching { BuildType.valueOf(Preference.getString(keyBuild(root), DEFAULT_BUILD_TYPE.name)) }
            .getOrDefault(DEFAULT_BUILD_TYPE)

    fun setBuildType(root: File, type: BuildType) = Preference.setString(keyBuild(root), type.name)

    // ---- Composed command -----------------------------------------------------------------------

    /**
     * The extra CLI arguments string handed to `project_runner.sh` — the chosen log-level flag
     * followed by every enabled additional flag, e.g. `"--info --stacktrace --offline"`. The flag
     * order follows [ADDITIONAL_FLAGS] so the generated command is stable.
     */
    fun gradleArgs(root: File): String {
        val parts = mutableListOf<String>()
        logLevel(root).arg.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        val enabled = selectedFlags(root)
        ADDITIONAL_FLAGS.forEach { if (enabled.contains(it.arg)) parts.add(it.arg) }
        return parts.joinToString(" ")
    }
}
