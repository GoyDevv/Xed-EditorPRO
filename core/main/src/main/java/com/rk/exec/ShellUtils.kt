package com.rk.exec

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ShellUtils {
    data class Result(val exitCode: Int, val output: String, val error: String, val timedOut: Boolean)

    /**
     * Run a command in the Linux sandbox and stream stdout+stderr line-by-line via [onLine] as it
     * arrives (for live progress views). Returns the exit code (-1 if it couldn't run). Never throws.
     */
    suspend fun runUbuntuStreaming(
        workingDir: String? = null,
        command: List<String>,
        timeoutSeconds: Long? = null,
        onLine: (String) -> Unit,
    ): Int =
        withContext(Dispatchers.IO) {
            runCatching {
                    val process = ubuntuProcess(workingDir = workingDir, command = command)
                    val outThread = Thread {
                        runCatching { process.inputStream.bufferedReader().forEachLine { onLine(it) } }
                    }
                    val errThread = Thread {
                        runCatching { process.errorStream.bufferedReader().forEachLine { onLine(it) } }
                    }
                    outThread.start()
                    errThread.start()
                    val code =
                        if (timeoutSeconds != null) {
                            if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                                process.exitValue()
                            } else {
                                process.destroyForcibly()
                                onLine("[!] timed out after ${timeoutSeconds}s")
                                -1
                            }
                        } else {
                            process.waitFor()
                        }
                    outThread.join()
                    errThread.join()
                    code
                }
                .getOrElse {
                    onLine("error: ${it.message}")
                    -1
                }
        }

    suspend fun run(vararg command: String, timeoutSeconds: Long? = null): Result =
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder(*command).start()

            val output = StringBuilder()
            val error = StringBuilder()

            val outputThread = Thread {
                runCatching { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
            }
            val errorThread = Thread {
                runCatching { process.errorStream.bufferedReader().forEachLine { error.appendLine(it) } }
            }

            outputThread.start()
            errorThread.start()

            val timedOut =
                if (timeoutSeconds != null) {
                    !process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                } else {
                    process.waitFor()
                    false
                }

            if (timedOut) {
                process.destroyForcibly()
            }

            outputThread.join()
            errorThread.join()

            Result(
                exitCode = if (timedOut) -1 else process.exitValue(),
                output = output.toString().trim(),
                error = error.toString().trim(),
                timedOut = timedOut,
            )
        }

    suspend fun runUbuntu(workingDir: String? = null, vararg command: String, timeoutSeconds: Long? = null): Result =
        withContext(Dispatchers.IO) {
            val process = ubuntuProcess(workingDir = workingDir, command = command.toList())

            val output = StringBuilder()
            val error = StringBuilder()

            val outputThread = Thread {
                runCatching { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
            }
            val errorThread = Thread {
                runCatching { process.errorStream.bufferedReader().forEachLine { error.appendLine(it) } }
            }

            outputThread.start()
            errorThread.start()

            val timedOut =
                if (timeoutSeconds != null) {
                    !process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                } else {
                    process.waitFor()
                    false
                }

            if (timedOut) {
                process.destroyForcibly()
            }

            outputThread.join()
            errorThread.join()

            Result(
                exitCode = if (timedOut) -1 else process.exitValue(),
                output = output.toString().trim(),
                error = error.toString().trim(),
                timedOut = timedOut,
            )
        }
}
