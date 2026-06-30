package com.rk.ai

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Client-side tools the AI can call (OpenAI function-calling). The model only *requests* these; the
 * app executes them locally — that's how a plain API key gets read/write/run abilities. Execution is
 * gated by the permission policy in [AiAgent].
 *
 * File tools operate on the real filesystem (relative paths resolve against the project root).
 * `run_command` runs in the persistent Linux-sandbox shell held by [AiTerminal].
 */
object AiTools {

    val names =
        listOf(
            "read_file",
            "write_file",
            "edit_file",
            "apply_patch",
            "create_dir",
            "list_dir",
            "glob_files",
            "search_text",
            "run_command",
            "delete_file",
            "move_file",
            "set_tasks",
            "complete_task",
            "fetch_url",
        )

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
            put(
                tool(
                    "search_text",
                    "Search the project for files containing the given text (recursive, case-insensitive).",
                    JSONObject()
                        .put("query", strProp("The text to search for."))
                        .put("path", strProp("Optional directory to search in (relative to the project root).")),
                    listOf("query"),
                )
            )
            put(
                tool(
                    "delete_file",
                    "Delete a file or directory.",
                    JSONObject().put("path", strProp("Path to delete, relative to the project root.")),
                    listOf("path"),
                )
            )
            put(
                tool(
                    "move_file",
                    "Move or rename a file/directory.",
                    JSONObject()
                        .put("from", strProp("Source path."))
                        .put("to", strProp("Destination path.")),
                    listOf("from", "to"),
                )
            )
            put(
                tool(
                    "edit_file",
                    "Make a targeted edit by replacing an exact substring in a file with new text.",
                    JSONObject()
                        .put("path", strProp("File path, relative to the project root."))
                        .put("old_string", strProp("The exact existing text to replace (include enough context to be unique)."))
                        .put("new_string", strProp("The replacement text."))
                        .put(
                            "replace_all",
                            JSONObject().apply { put("type", "boolean"); put("description", "Replace all occurrences (default false).") },
                        ),
                    listOf("path", "old_string", "new_string"),
                )
            )
            put(
                tool(
                    "apply_patch",
                    "Apply a unified-diff patch (one or more @@ hunks) to a single existing file. " +
                        "Prefer this for multi-hunk edits. The patch body uses lines prefixed with a space " +
                        "(context), '-' (remove) or '+' (add), under '@@ -old,len +new,len @@' headers. " +
                        "Context/removed lines must match the file exactly or the patch is rejected.",
                    JSONObject()
                        .put("path", strProp("File path, relative to the project root."))
                        .put("patch", strProp("Unified diff body (the @@ hunks; no need for ---/+++ header lines).")),
                    listOf("path", "patch"),
                )
            )
            put(
                tool(
                    "glob_files",
                    "Find files matching a glob pattern (e.g. *.kt, src/**/*.java).",
                    JSONObject()
                        .put("pattern", strProp("Glob pattern (* and ? wildcards; ** matches any depth)."))
                        .put("path", strProp("Optional base directory, relative to the project root.")),
                    listOf("pattern"),
                )
            )
            put(
                tool(
                    "create_dir",
                    "Create a directory (and any missing parents).",
                    JSONObject().put("path", strProp("Directory path, relative to the project root.")),
                    listOf("path"),
                )
            )
            put(
                tool(
                    "set_tasks",
                    "Create/replace the task plan for the current work. Call this first with all the " +
                        "steps you intend to do, then mark each done with complete_task as you finish it.",
                    JSONObject().put(
                        "tasks",
                        JSONObject().apply {
                            put("type", "array")
                            put("items", JSONObject().put("type", "string"))
                            put("description", "Ordered list of short task descriptions.")
                        },
                    ),
                    listOf("tasks"),
                )
            )
            put(
                tool(
                    "complete_task",
                    "Mark a task done (by its 1-based number) once you've finished it.",
                    JSONObject().put(
                        "index",
                        JSONObject().apply {
                            put("type", "integer")
                            put("description", "1-based task number to mark complete.")
                        },
                    ),
                    listOf("index"),
                )
            )
            put(
                tool(
                    "fetch_url",
                    "Fetch the contents of a web URL (for docs, references, etc.).",
                    JSONObject().put("url", strProp("The http(s) URL to fetch.")),
                    listOf("url"),
                )
            )
            // Merge in any tools exposed by connected MCP servers (named mcp__server__tool).
            val mcp = AiMcp.toolSchemas()
            for (i in 0 until mcp.length()) put(mcp.getJSONObject(i))
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
            "search_text" -> "Search: ${args?.optString("query")}"
            "delete_file" -> "Delete ${args?.optString("path")}"
            "move_file" -> "Move ${args?.optString("from")} → ${args?.optString("to")}"
            "edit_file" -> "Edit ${args?.optString("path")}"
            "apply_patch" -> "Patch ${args?.optString("path")}"
            "glob_files" -> "Find ${args?.optString("pattern")}"
            "create_dir" -> "Create dir ${args?.optString("path")}"
            "set_tasks" -> "Plan tasks"
            "complete_task" -> "Complete task ${args?.optInt("index")}"
            "fetch_url" -> "Fetch ${args?.optString("url")}"
            else -> if (AiMcp.isMcpTool(call.name)) AiMcp.describe(call.name) else call.name
        }
    }

    private fun resolve(workingDir: String, path: String): File =
        if (path.startsWith("/")) File(path) else File(workingDir, path)

    /**
     * Apply a unified-diff [patch] (one or more `@@` hunks) to [original] and return the new text.
     * Context (' ') and removed ('-') lines are verified against the source; on any mismatch this
     * throws [IllegalStateException] (caught by [execute]) so a bad patch never corrupts the file.
     */
    private fun applyUnifiedDiff(original: String, patch: String): String {
        val nl = if (original.contains("\r\n")) "\r\n" else "\n"
        val orig = original.split("\n").map { it.removeSuffix("\r") }
        val patchLines = patch.split("\n").map { it.removeSuffix("\r") }
        val out = ArrayList<String>()
        var oi = 0 // 0-based index into orig
        var pi = 0
        var sawHunk = false
        while (pi < patchLines.size) {
            val line = patchLines[pi]
            if (line.startsWith("@@")) {
                sawHunk = true
                val m = Regex("@@\\s*-(\\d+)(?:,(\\d+))?\\s*\\+(\\d+)(?:,(\\d+))?\\s*@@").find(line)
                val oldStart = (m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (oi + 1)) - 1
                // Copy untouched lines before the hunk.
                while (oi < oldStart && oi < orig.size) {
                    out.add(orig[oi]); oi++
                }
                pi++
                while (pi < patchLines.size && !patchLines[pi].startsWith("@@")) {
                    val pl = patchLines[pi]
                    when {
                        pl.startsWith("\\") -> {} // "\ No newline at end of file"
                        pl.startsWith("+") -> out.add(pl.substring(1))
                        pl.startsWith("-") -> {
                            val expected = pl.substring(1)
                            if (oi >= orig.size || orig[oi] != expected)
                                error("patch context mismatch near source line ${oi + 1}: expected to remove \"$expected\"")
                            oi++
                        }
                        pl.startsWith(" ") -> {
                            val expected = pl.substring(1)
                            if (oi >= orig.size || orig[oi] != expected)
                                error("patch context mismatch near source line ${oi + 1}: expected \"$expected\"")
                            out.add(orig[oi]); oi++
                        }
                        pl.isEmpty() -> {
                            // Treat a bare empty line as a blank context line.
                            if (oi < orig.size && orig[oi].isEmpty()) { out.add(""); oi++ } else out.add("")
                        }
                        else -> error("unexpected patch line: \"$pl\"")
                    }
                    pi++
                }
            } else {
                pi++ // skip ---/+++ headers or stray lines outside hunks
            }
        }
        if (!sawHunk) error("no @@ hunks found in patch")
        while (oi < orig.size) { out.add(orig[oi]); oi++ }
        return out.joinToString(nl)
    }

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
                        if (cmd.isBlank()) "Error: command is empty"
                        else AiTerminal.run(cmd, workingDir)
                    }
                    "search_text" -> {
                        val q = args.optString("query")
                        if (q.isBlank()) return@runCatching "Error: query is empty"
                        val base = resolve(workingDir, args.optString("path").ifBlank { "." })
                        val skip = setOf(".git", "node_modules", "build", ".gradle", ".cxx")
                        val matches = ArrayList<String>()
                        base.walkTopDown()
                            .onEnter { it.name !in skip }
                            .filter { it.isFile && it.length() in 1..1_000_000 }
                            .forEach { f ->
                                if (matches.size >= 200) return@forEach
                                runCatching {
                                    f.readText().lineSequence().forEachIndexed { i, line ->
                                        if (matches.size < 200 && line.contains(q, ignoreCase = true)) {
                                            matches.add("${f.toRelativeString(File(workingDir))}:${i + 1}: ${line.trim().take(200)}")
                                        }
                                    }
                                }
                            }
                        if (matches.isEmpty()) "No matches for \"$q\"." else matches.joinToString("\n")
                    }
                    "delete_file" -> {
                        val f = resolve(workingDir, args.optString("path"))
                        if (!f.exists()) "Error: not found: ${f.absolutePath}"
                        else if (f.deleteRecursively()) "Deleted ${f.absolutePath}" else "Error: could not delete ${f.absolutePath}"
                    }
                    "move_file" -> {
                        val from = resolve(workingDir, args.optString("from"))
                        val to = resolve(workingDir, args.optString("to"))
                        if (!from.exists()) "Error: source not found: ${from.absolutePath}"
                        else {
                            to.parentFile?.mkdirs()
                            if (from.renameTo(to)) "Moved to ${to.absolutePath}" else "Error: move failed"
                        }
                    }
                    "edit_file" -> {
                        val f = resolve(workingDir, args.optString("path"))
                        val old = args.optString("old_string")
                        val new = args.optString("new_string")
                        if (!f.isFile) "Error: file not found: ${f.absolutePath}"
                        else if (old.isEmpty()) "Error: old_string is empty"
                        else {
                            val text = f.readText()
                            if (!text.contains(old)) "Error: old_string not found in ${f.name}"
                            else {
                                val updated =
                                    if (args.optBoolean("replace_all", false)) text.replace(old, new)
                                    else text.replaceFirst(old, new)
                                f.writeText(updated)
                                "Edited ${f.absolutePath}"
                            }
                        }
                    }
                    "apply_patch" -> {
                        val f = resolve(workingDir, args.optString("path"))
                        val patch = args.optString("patch")
                        if (!f.isFile) "Error: file not found: ${f.absolutePath}"
                        else if (patch.isBlank()) "Error: patch is empty"
                        else {
                            val updated = applyUnifiedDiff(f.readText(), patch) // throws on mismatch
                            f.writeText(updated)
                            "Patched ${f.absolutePath}"
                        }
                    }
                    "glob_files" -> {
                        val pattern = args.optString("pattern")
                        if (pattern.isBlank()) return@runCatching "Error: pattern is empty"
                        val base = resolve(workingDir, args.optString("path").ifBlank { "." })
                        val regex =
                            Regex(
                                "^" +
                                    Regex.escape(pattern)
                                        .replace("\\*\\*", "::DS::")
                                        .replace("\\*", "[^/]*")
                                        .replace("\\?", ".")
                                        .replace("::DS::", ".*") +
                                    "$"
                            )
                        val skip = setOf(".git", "node_modules", "build", ".gradle", ".cxx")
                        val out = ArrayList<String>()
                        base.walkTopDown()
                            .onEnter { it.name !in skip }
                            .filter { it.isFile }
                            .forEach { f ->
                                if (out.size >= 200) return@forEach
                                val rel = f.toRelativeString(base)
                                if (regex.matches(rel) || regex.matches(f.name)) out.add(f.toRelativeString(File(workingDir)))
                            }
                        if (out.isEmpty()) "No files match \"$pattern\"." else out.joinToString("\n")
                    }
                    "create_dir" -> {
                        val d = resolve(workingDir, args.optString("path"))
                        if (d.mkdirs() || d.isDirectory) "Created ${d.absolutePath}" else "Error: could not create ${d.absolutePath}"
                    }
                    "set_tasks" -> {
                        val arr = args.optJSONArray("tasks")
                        val items =
                            if (arr == null) emptyList()
                            else (0 until arr.length()).map { arr.optString(it) }
                        AiTaskStore.set(items)
                        "Task plan set:\n" + AiTaskStore.render()
                    }
                    "complete_task" -> {
                        AiTaskStore.complete(args.optInt("index", 0))
                        "Updated tasks:\n" + AiTaskStore.render()
                    }
                    "fetch_url" -> {
                        val url = args.optString("url")
                        if (!url.startsWith("http")) "Error: invalid url"
                        else
                            withContext(Dispatchers.IO) {
                                val http =
                                    OkHttpClient.Builder()
                                        .connectTimeout(20, TimeUnit.SECONDS)
                                        .readTimeout(30, TimeUnit.SECONDS)
                                        .build()
                                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                                    val body = resp.body.string()
                                    if (!resp.isSuccessful) "Error ${resp.code} fetching $url"
                                    else body.take(20_000)
                                }
                            }
                    }
                    else -> if (AiMcp.isMcpTool(call.name)) AiMcp.call(call.name, call.arguments) else "Unknown tool: ${call.name}"
                }
            }
            .getOrElse { "Error: ${it.message}" }
    }
}
