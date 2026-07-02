package com.rk.projects

import com.rk.exec.ShellUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads and switches the sandbox toolchain versions for the IDE Configuration view.
 *
 * - JDK: switched system-wide via `update-alternatives` (java + javac) — reliable on the apt sandbox.
 * - Python / build-tools: listed with the active one marked (informational; switching Python
 *   system-wide can break apt, so per-project virtualenvs are recommended instead).
 * - NDK: installed versions are listed; a version can be pinned to the current project by writing
 *   `ndk.dir` into its `local.properties`.
 *
 * Everything runs in the Ubuntu sandbox via [ShellUtils.runUbuntu]; failures are returned, not thrown.
 */
object IdeConfig {
    data class Ver(val label: String, val path: String, val active: Boolean)

    private suspend fun sh(cmd: String, timeout: Long = 20L): ShellUtils.Result =
        ShellUtils.runUbuntu(command = arrayOf("bash", "-lc", cmd), timeoutSeconds = timeout)

    // ---- JDK ------------------------------------------------------------------------------------
    suspend fun jdks(): List<Ver> =
        withContext(Dispatchers.IO) {
            val active = sh("readlink -f \"\$(command -v javac 2>/dev/null || command -v java 2>/dev/null)\" 2>/dev/null").output.trim()
            val list = sh("for d in /usr/lib/jvm/*/; do [ -x \"\${d}bin/javac\" ] && echo \"\$d\"; done 2>/dev/null").output
            list.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { dir ->
                val norm = dir.trimEnd('/')
                Ver(label = norm.substringAfterLast('/'), path = norm, active = active.isNotEmpty() && active.startsWith("$norm/"))
            }
        }

    suspend fun setJdk(dir: String): Boolean =
        withContext(Dispatchers.IO) {
            val d = dir.trimEnd('/')
            val cmd =
                "update-alternatives --install /usr/bin/java java \"$d/bin/java\" 100 >/dev/null 2>&1; " +
                    "update-alternatives --install /usr/bin/javac javac \"$d/bin/javac\" 100 >/dev/null 2>&1; " +
                    "update-alternatives --set java \"$d/bin/java\" && update-alternatives --set javac \"$d/bin/javac\""
            sh(cmd, 30L).exitCode == 0
        }

    // ---- Python (informational) -----------------------------------------------------------------
    suspend fun pythons(): List<Ver> =
        withContext(Dispatchers.IO) {
            val activeLabel = sh("readlink -f \"\$(command -v python3 2>/dev/null)\" 2>/dev/null").output.trim().substringAfterLast('/')
            val list = sh("ls /usr/bin/python3.[0-9]* 2>/dev/null").output
            list.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { p ->
                val label = p.substringAfterLast('/')
                Ver(label = label, path = p, active = label == activeLabel)
            }
        }

    // ---- Android NDK ----------------------------------------------------------------------------
    suspend fun ndks(projectRoot: File): List<Ver> =
        withContext(Dispatchers.IO) {
            val list = sh("ls \"\$HOME/android-sdk/ndk\" 2>/dev/null").output
            val pinned = readNdkPin(projectRoot)
            list.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.map { v ->
                Ver(label = v, path = v, active = v == pinned)
            }
        }

    /** Reads the version pinned in the project's local.properties (ndk.dir), or null. */
    private fun readNdkPin(projectRoot: File): String? =
        runCatching {
            val lp = File(projectRoot, "local.properties")
            if (!lp.exists()) return null
            lp.readLines().firstOrNull { it.trimStart().startsWith("ndk.dir") }
                ?.substringAfter('=')?.trim()?.trimEnd('/')?.substringAfterLast('/')
        }.getOrNull()

    /** Pins [version] to the current project by writing ndk.dir into local.properties. */
    suspend fun setProjectNdk(projectRoot: File, version: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val ndkPath = sh("echo \"\$HOME/android-sdk/ndk/$version\"").output.trim()
                if (ndkPath.isBlank()) return@withContext false
                val lp = File(projectRoot, "local.properties")
                val lines = if (lp.exists()) lp.readLines().filterNot { it.trimStart().startsWith("ndk.dir") } else emptyList()
                lp.writeText((lines + "ndk.dir=$ndkPath").joinToString("\n") + "\n")
                true
            }.getOrDefault(false)
        }

    // ---- Android build-tools (informational) ----------------------------------------------------
    suspend fun buildTools(): List<Ver> =
        withContext(Dispatchers.IO) {
            val list = sh("ls \"\$HOME/android-sdk/build-tools\" 2>/dev/null").output
            list.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.map { Ver(label = it, path = it, active = false) }
        }
}
