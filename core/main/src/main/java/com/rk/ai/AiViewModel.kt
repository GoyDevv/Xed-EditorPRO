package com.rk.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Drives the AI agent: holds chat sessions, runs the OpenAI tool-calling loop, and gates tool
 * execution behind the permission policy (allow / deny / never-ask). UI observes the Compose state.
 */
class AiViewModel : ViewModel() {

    class Session(val id: String, title: String) {
        var title by mutableStateOf(title)
        val messages: SnapshotStateList<AiMessage> = mutableStateListOf()
        var totalTokens by mutableStateOf(0)
    }

    val sessions = mutableStateListOf<Session>()
    var currentSessionId by mutableStateOf<String?>(null)
        private set

    val current: Session?
        get() = sessions.firstOrNull { it.id == currentSessionId }

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)

    // Provider / model
    var providerId by mutableStateOf(AiPrefs.selectedProviderId)
        private set

    var model by mutableStateOf(AiPrefs.selectedModel.ifBlank { AiProviders.byId(AiPrefs.selectedProviderId).defaultModel })
        private set

    var models by mutableStateOf<List<String>>(emptyList())
        private set

    var loadingModels by mutableStateOf(false)
        private set

    // Permission gate
    data class PermissionRequest(val toolName: String, val description: String)

    var pendingPermission by mutableStateOf<PermissionRequest?>(null)
        private set

    private var permissionDeferred: CompletableDeferred<Boolean>? = null
    private var runJob: kotlinx.coroutines.Job? = null

    /** Cancel the currently-running agent turn. */
    fun stop() {
        runJob?.cancel()
        runJob = null
        AiClient.cancel()
        permissionDeferred?.complete(false)
        pendingPermission = null
        busy = false
        com.rk.utils.application?.let { AiAgentService.stop(it) }
    }

    init {
        // Stop the agent turn when the user taps Stop on the notification.
        AiAgentBus.onStop = { stop() }
        // Restore saved chats so nothing is lost across restarts (small file; fast).
        AiSessionStore.loadAll().forEach { s ->
            val session = Session(s.id, s.title)
            session.totalTokens = s.totalTokens
            session.messages.addAll(s.messages)
            sessions.add(session)
        }
        currentSessionId = sessions.firstOrNull()?.id
    }

    private fun persist() {
        val snapshot =
            sessions.map { AiSessionStore.StoredSession(it.id, it.title, it.totalTokens, it.messages.toList()) }
        viewModelScope.launch(Dispatchers.IO) { AiSessionStore.saveAll(snapshot) }
    }

    val provider: AiProvider
        get() = AiProviders.byId(providerId)

    /** Kiro native mode: Kiro selected and no gateway base URL configured. */
    fun isKiroNative(): Boolean =
        providerId == AiProviders.KIRO.id && AiPrefs.getBaseUrl(providerId).isBlank()

    /** Whether the current provider is ready to use (has a key, or Kiro is logged in / discoverable). */
    fun isConfigured(): Boolean =
        AiPrefs.hasKey(providerId) || (isKiroNative() && KiroAuth.hasDiscoverableCreds())

    /** Session context usage as a percentage of the model's context window. */
    val percentUsed: Int
        get() {
            val total = current?.totalTokens ?: 0
            val window = AiProviders.contextWindow(model).coerceAtLeast(1)
            return ((total.toFloat() / window) * 100f).toInt().coerceIn(0, 100)
        }

    fun selectProvider(id: String) {
        providerId = id
        AiPrefs.selectedProviderId = id
        model = AiPrefs.selectedModel.ifBlank { AiProviders.byId(id).defaultModel }
        models = emptyList()
    }

    fun selectModel(m: String) {
        model = m
        AiPrefs.selectedModel = m
    }

    fun refreshModels() {
        val key = AiPrefs.getKey(providerId)
        if (key.isBlank() && !isKiroNative()) return
        loadingModels = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { AiClient.listModels(provider, key) }
                .onSuccess { list ->
                    models = list
                    if (model.isBlank() && list.isNotEmpty()) selectModel(list.first())
                }
                .onFailure { error = it.message }
            loadingModels = false
        }
    }

    fun newSession() {
        val s = Session(id = System.currentTimeMillis().toString(), title = "New chat")
        sessions.add(0, s)
        currentSessionId = s.id
        AiTaskStore.clear()
        AiTerminal.shutdown()
        persist()
    }

    fun selectSession(id: String) {
        currentSessionId = id
    }

    /** Clears the current chat's messages (used by the /clear slash command). */
    fun clearCurrent() {
        current?.messages?.clear()
        current?.totalTokens = 0
        persist()
    }

    /** The most recent assistant reply text (used by /copy). */
    fun lastAssistant(): String = current?.messages?.lastOrNull { it.role == "assistant" }?.content ?: ""

    /** The most recent user message text (used by /retry). */
    fun lastUserText(): String = current?.messages?.lastOrNull { it.role == "user" }?.content ?: ""

    /**
     * Edit a previously-sent user message and resend it: drops that message and everything after it
     * (the old assistant reply + tool results), then sends [newText] fresh. The system prompt and
     * earlier history are kept.
     */
    fun editAndResend(message: AiMessage, newText: String, workingDir: String) {
        if (busy || newText.isBlank()) return
        val session = current ?: return
        val idx = session.messages.indexOfFirst { it === message }
        if (idx < 0) return
        while (session.messages.size > idx) session.messages.removeAt(session.messages.size - 1)
        persist()
        send(newText, workingDir)
    }

    private fun ensureSession(): Session = current ?: Session(id = System.currentTimeMillis().toString(), title = "New chat").also {
        sessions.add(0, it)
        currentSessionId = it.id
    }

    private fun systemPrompt(workingDir: String): AiMessage =
        AiMessage(
            role = "system",
            content =
                "You are Xed, a coding agent embedded in the Xed-Editor Android IDE. " +
                    "You can read and write files and run shell commands in the user's Linux sandbox using the provided tools. " +
                    "The current project directory is: $workingDir. " +
                    "run_command uses a persistent shell: your working directory, environment variables and " +
                    "activated environments carry over between commands and turns (the user may kill this terminal; " +
                    "if so it is transparently restarted and you will be told). " +
                    "For multi-hunk edits prefer apply_patch (a unified diff); use edit_file for a single targeted change. " +
                    "Prefer using tools to inspect the project before answering. Keep responses concise. " +
                    "For multi-step work, call set_tasks first with your plan, then mark each done with " +
                    "complete_task as you finish it. " +
                    "Explain what you're about to do before running commands, and avoid destructive actions unless asked.",
        )

    /** Send a user message and run the agent loop. [workingDir] is the project's real path. */
    fun send(text: String, workingDir: String) {
        if (busy || text.isBlank()) return
        val key = AiPrefs.getKey(providerId)
        if (key.isBlank() && !isKiroNative()) {
            error = "No API key set for ${provider.label}."
            return
        }
        if (model.isBlank()) {
            error = "No model selected."
            return
        }

        val session = ensureSession()
        if (session.messages.none { it.role == "system" }) session.messages.add(systemPrompt(workingDir))
        session.messages.add(AiMessage(role = "user", content = text))
        if (session.title == "New chat") session.title = text.take(40)

        busy = true
        error = null
        com.rk.utils.application?.let { AiAgentService.start(it, "Working · $model") }
        runJob =
            viewModelScope.launch {
                runCatching { AiMcp.ensureConnected() } // optional; no-op if no servers configured
                runCatching { runAgentLoop(session, key, workingDir) }
                    .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message }
                busy = false
                com.rk.utils.application?.let { AiAgentService.stop(it) }
                persist()
            }
    }

    private suspend fun runAgentLoop(session: Session, key: String, workingDir: String) {
        var iterations = 0
        while (iterations < 16) {
            iterations++
            // Insert a live placeholder; stream text into it, then finalize with tool calls.
            val idx = session.messages.size
            session.messages.add(AiMessage(role = "assistant", content = "", ui = "text"))
            val convo = session.messages.subList(0, idx).toList()
            val sb = StringBuilder()
            val result =
                AiClient.chatStream(provider, key, model, convo, AiTools.schemas()) { frag ->
                    sb.append(frag)
                    if (idx < session.messages.size) session.messages[idx] = session.messages[idx].copy(content = sb.toString())
                }
            session.totalTokens = result.totalTokens
            session.messages[idx] = result.message // final content + tool calls

            if (result.message.toolCalls.isEmpty()) break

            for (call in result.message.toolCalls) {
                val allowed = askPermission(call)
                if (allowed) com.rk.utils.application?.let { AiAgentService.start(it, AiTools.describe(call)) }
                val output = if (allowed) AiTools.execute(call, workingDir) else "Permission denied by the user."
                session.messages.add(
                    AiMessage(role = "tool", content = output, toolCallId = call.id, ui = "tool_result")
                )
            }
        }
    }

    private suspend fun askPermission(call: AiToolCall): Boolean {
        // Task-planning and read-only tools never need a prompt.
        if (call.name in setOf("set_tasks", "complete_task", "read_file", "list_dir", "search_text", "glob_files")) {
            return true
        }
        return when (AiPrefs.getPolicy(call.name)) {
            AiPrefs.Policy.ALWAYS -> true
            AiPrefs.Policy.NEVER -> false
            AiPrefs.Policy.ASK -> {
                val deferred = CompletableDeferred<Boolean>()
                permissionDeferred = deferred
                pendingPermission = PermissionRequest(call.name, AiTools.describe(call))
                val ok = deferred.await()
                pendingPermission = null
                permissionDeferred = null
                ok
            }
        }
    }

    /** Called by the permission dialog. [remember] persists the choice for this tool. */
    fun resolvePermission(allow: Boolean, remember: Boolean) {
        val req = pendingPermission
        if (remember && req != null) {
            AiPrefs.setPolicy(req.toolName, if (allow) AiPrefs.Policy.ALWAYS else AiPrefs.Policy.NEVER)
        }
        permissionDeferred?.complete(allow)
    }
}
