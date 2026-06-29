package com.rk.ai

import com.rk.exec.ShellUtils
import com.rk.runner.ProjectRunner
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client-side tools the AI can call (OpenAI function-calling). The model only *requests* these; the
 * app executes them locally — that's how a plain API key gets read/write/run abilities. Execution is
 * gated by the permission policy in [AiAgent].
 *
 * File tools operate on the real filesystem (relative paths resolve against the project root).
 * `run_command` runs in the Linux sandbox via [ShellUtils.runUbuntu].
 */
object AiTools {

    val names = listOf("read_file", "write_file", "list_dir", "run_command")

    private const val MAX_READ = 100_000 // chars, to keep context sane

    /** The tool schema array sent to the model. */
    fun schemas(): JSONArray {
        fun tool(name: String, description: String, props: JSONObject, required: List<String>): JSONObject =
            JSONObject().apply {
                put("type", "function")
                put(
                    "function",
                    JSONObject().apply {
                        put("name", name)
                        put("description", description)
                        put(
                            "parameters",
                            JSONObject().apply {
                                put("type", "object")
                                put("properties", props)
                                put("required", JSONArray(required))
                            },
                        )
                    },
                )
            }

        fun strProp(desc: String) = JSONObject().apply { put("type", "string"); put("description", desc) }

        return JSONArray().apply {
            put(
                tool(
                    "read_file",
                    "Read the contents of a text file in the project.",
                    JSONObject().put("path", strProp("File path, absolute or relative to the project root.")),
                    listOf("path"),
                )
            )
            put(
                tool(
                    "write_file",
                    "Create or overwrite a text file with the given content.",
                    JSONObject()
                        .put("path", strProp("File path, absolute or relative to the project root."))
                        .put("content", strProp("The full new content of the file.")),
                    listOf("path", "content"),
                )
            )
            put(
                tool(
                    "list_dir",
                    "List files and folders in a directory.",
                    JSONObject().put("path", strProp("Directory path, absolute or relative to the project root.")),
                    listOf("path"),
                )
            )
            put(
                tool(
                    "run_command",
                    "Run a shell command in the project's Linux sandbox and return its output.",
                    JSONObject().put("command", strProp("The shell command to run (bash).")),
                    listOf("command"),
                )
            )
        }
    }

    /** Short, human-readable summary of a tool call for the permission dialog / UI. */
    fun describe(call: AiToolCall): String {
        val args = runCatching { JSONObject(call.arguments) }.getOrNull()
        return when (call.name) {
            "read_file" -> "Read ${args?.optString("path")}"
            "write_file" -> "Write ${args?.optString("path")}"
            "list_dir" -> "List ${args?.optString("path")}"
            "run_command" -> "Run: ${args?.optString("command")}"
            else -> call.name
        }
    }

    private fun resolve(workingDir: String, path: String): File =
        if (path.startsWith("/")) File(path) else File(workingDir, path)

    /** Execute a tool call. Returns the result text fed back to the model. Never throws. */
    suspend fun execute(call: AiToolCall, workingDir: String): String {
        val args = runCatching { JSONObject(call.arguments) }.getOrNull() ?: JSONObject()
        return runCatching {
                when (call.name) {
                    "read_file" -> {
                        val f = resolve(workingDir, args.optString("path"))
                        if (!f.exists()) "Error: file not found: ${f.absolutePath}"
                        else if (!f.isFile) "Error: not a file: ${f.absolutePath}"
                        else f.readText().take(MAX_READ)
                    }
                    "write_file" -> {
                        val f = resolve(workingDir, args.optString("path"))
                        f.parentFile?.mkdirs()
                        f.writeText(args.optString("content"))
                        "Wrote ${f.length()} bytes to ${f.absolutePath}"
                    }
                    "list_dir" -> {
                        val d = resolve(workingDir, args.optString("path").ifBlank { "." })
                        if (!d.isDirectory) "Error: not a directory: ${d.absolutePath}"
                        else
                            d.listFiles()
                                ?.sortedBy { it.name }
                                ?.joinToString("\n") { (if (it.isDirectory) "[dir] " else "      ") + it.name }
                                ?: "(empty)"
                    }
                    "run_command" -> {
                        val cmd = args.optString("command")
                        val sandboxDir = ProjectRunner.toSandboxPath(workingDir)
                        val res =
                            ShellUtils.runUbuntu(
                                workingDir = sandboxDir,
                                command = arrayOf("bash", "-lc", cmd),
                                timeoutSeconds = 600L,
                            )
                        buildString {
                            if (res.output.isNotBlank()) appendLine(res.output)
                            if (res.error.isNotBlank()) appendLine(res.error)
                            append(if (res.timedOut) "[timed out]" else "[exit ${res.exitCode}]")
                        }
                    }
                    else -> "Unknown tool: ${call.name}"
                }
            }
            .getOrElse { "Error: ${it.message}" }
    }
}
