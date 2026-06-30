package com.rk.ai

import com.rk.exec.ubuntuProcess
import com.rk.runner.ProjectRunner
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional **Model Context Protocol** support: lets the agent use tools exposed by external MCP
 * servers (e.g. `@modelcontextprotocol/server-filesystem`, `server-git`, …). Each server is a command
 * the user configures in AI settings; it runs in the Linux sandbox and speaks JSON-RPC 2.0 over
 * newline-delimited stdio (the MCP stdio transport).
 *
 * Discovered tools are surfaced to the model with names `mcp__<server>__<tool>` and merged into the
 * normal tool list, so the model calls them exactly like the built-in tools and the same permission
 * gate applies. Everything is wrapped in runCatching: if no servers are configured, or any server
 * fails, the core agent is unaffected.
 */
object AiMcp {
    data class Server(val name: String, val command: String)

    data class McpTool(val server: String, val name: String, val description: String, val schema: JSONObject)

    private class Connection(
        val server: Server,
        val process: Process,
        val writer: OutputStreamWriter,
        val reader: BufferedReader,
    ) {
        var nextId = 1
        val tools = ArrayList<McpTool>()
    }

    private val connections = HashMap<String, Connection>()
    private val mutex = Mutex()

    /** Parsed server list from prefs (never throws). */
    fun servers(): List<Server> =
        runCatching {
                val raw = AiPrefs.mcpServersRaw
                if (raw.isBlank()) return emptyList()
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = o.optString("name").trim()
                    val cmd = o.optString("command").trim()
                    if (name.isBlank() || cmd.isBlank()) null else Server(name, cmd)
                }
            }
            .getOrElse { emptyList() }

    fun setServers(list: List<Server>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("command", it.command)) }
        AiPrefs.mcpServersRaw = if (list.isEmpty()) "" else arr.toString()
    }

    fun isMcpTool(name: String): Boolean = name.startsWith("mcp__")

    /** OpenAI tool schemas for every connected MCP tool (call [ensureConnected] first). */
    fun toolSchemas(): JSONArray {
        val arr = JSONArray()
        connections.values.forEach { conn ->
            conn.tools.forEach { t ->
                val params =
                    if (t.schema.length() == 0) JSONObject().put("type", "object").put("properties", JSONObject())
                    else t.schema
                arr.put(
                    JSONObject().apply {
                        put("type", "function")
                        put(
                            "function",
                            JSONObject().apply {
                                put("name", "mcp__${t.server}__${t.name}")
                                put("description", "[MCP ${t.server}] ${t.description}")
                                put("parameters", params)
                            },
                        )
                    }
                )
            }
        }
        return arr
    }

    fun describe(toolFullName: String): String {
        val rest = toolFullName.removePrefix("mcp__")
        val server = rest.substringBefore("__", "")
        val tool = rest.substringAfter("__", rest)
        return "MCP $server: $tool"
    }

    /** Connect to any not-yet-connected configured servers and list their tools. Safe to call often. */
    suspend fun ensureConnected() {
        val list = servers()
        if (list.isEmpty()) {
            if (connections.isNotEmpty()) shutdown()
            return
        }
        mutex.withLock {
            // Drop connections for servers that were removed.
            val names = list.map { it.name }.toSet()
            connections.keys.filter { it !in names }.toList().forEach { closeConnection(it) }
            for (server in list) {
                val existing = connections[server.name]
                if (existing != null && existing.process.isAlive) continue
                if (existing != null) closeConnection(server.name)
                runCatching { connect(server) }.onSuccess { connections[server.name] = it }
            }
        }
    }

    private suspend fun connect(server: Server): Connection =
        withContext(Dispatchers.IO) {
            val workingDir = ProjectRunner.toSandboxPath(com.rk.file.sandboxHomeDir().absolutePath)
            val tokens = tokenize(server.command)
            val p = ubuntuProcess(workingDir = workingDir, command = tokens)
            val conn =
                Connection(
                    server = server,
                    process = p,
                    writer = OutputStreamWriter(p.outputStream),
                    reader = BufferedReader(InputStreamReader(p.inputStream)),
                )
            // initialize handshake
            request(
                conn,
                "initialize",
                JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", JSONObject())
                    put("clientInfo", JSONObject().put("name", "Xed-Editor").put("version", "1"))
                },
            )
            notify(conn, "notifications/initialized", JSONObject())
            // tools/list
            val res = request(conn, "tools/list", JSONObject())
            val toolsArr = res.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until toolsArr.length()) {
                val t = toolsArr.optJSONObject(i) ?: continue
                conn.tools.add(
                    McpTool(
                        server = server.name,
                        name = t.optString("name"),
                        description = t.optString("description", ""),
                        schema = t.optJSONObject("inputSchema") ?: JSONObject(),
                    )
                )
            }
            conn
        }

    /** Execute an `mcp__server__tool` call. Returns text fed back to the model. Never throws. */
    suspend fun call(toolFullName: String, arguments: String): String =
        runCatching {
                val rest = toolFullName.removePrefix("mcp__")
                val serverName = rest.substringBefore("__")
                val toolName = rest.substringAfter("__")
                val conn = connections[serverName] ?: return@runCatching "Error: MCP server '$serverName' not connected."
                if (!conn.process.isAlive) return@runCatching "Error: MCP server '$serverName' is not running."
                val argsObj = runCatching { JSONObject(arguments) }.getOrDefault(JSONObject())
                val res =
                    request(
                        conn,
                        "tools/call",
                        JSONObject().put("name", toolName).put("arguments", argsObj),
                    )
                val result = res.optJSONObject("result")
                if (result == null) {
                    val err = res.optJSONObject("error")?.optString("message")
                    return@runCatching "MCP error: ${err ?: res}"
                }
                val content = result.optJSONArray("content") ?: JSONArray()
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val c = content.optJSONObject(i) ?: continue
                    when (c.optString("type")) {
                        "text" -> sb.appendLine(c.optString("text"))
                        else -> sb.appendLine(c.toString())
                    }
                }
                sb.toString().ifBlank { "(no content)" }.take(20_000)
            }
            .getOrElse { "Error calling MCP tool: ${it.message}" }

    // --- JSON-RPC over stdio ------------------------------------------------------------------

    private suspend fun request(conn: Connection, method: String, params: JSONObject, timeoutMs: Long = 30_000): JSONObject =
        withContext(Dispatchers.IO) {
            val id = conn.nextId++
            val msg = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
            conn.writer.write(msg.toString())
            conn.writer.write("\n")
            conn.writer.flush()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!conn.reader.ready()) {
                    delay(15)
                    continue
                }
                val line = conn.reader.readLine() ?: break
                if (line.isBlank()) continue
                val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                // Match our response id; ignore notifications/logs/other ids.
                if (obj.has("id") && obj.opt("id").toString() == id.toString()) return@withContext obj
            }
            JSONObject().put("error", JSONObject().put("message", "timeout or no response for $method"))
        }

    private suspend fun notify(conn: Connection, method: String, params: JSONObject) =
        withContext(Dispatchers.IO) {
            val msg = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
            conn.writer.write(msg.toString())
            conn.writer.write("\n")
            conn.writer.flush()
        }

    private fun tokenize(command: String): List<String> {
        // Minimal shell-style tokenizer honoring single/double quotes.
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        for (ch in command) {
            when {
                quote != null -> if (ch == quote) quote = null else sb.append(ch)
                ch == '\'' || ch == '"' -> quote = ch
                ch.isWhitespace() -> if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun closeConnection(name: String) {
        connections.remove(name)?.let { conn ->
            runCatching { conn.writer.close() }
            runCatching { conn.process.destroyForcibly() }
        }
    }

    fun shutdown() {
        connections.keys.toList().forEach { closeConnection(it) }
    }
}
