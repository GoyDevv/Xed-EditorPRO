package com.rk.ai

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blankj.utilcode.util.ClipboardUtils
import com.rk.activities.main.MainActivity
import com.rk.components.isDrawerExpanded
import com.rk.drawer.DrawerTab
import com.rk.file.sandboxHomeDir
import com.rk.filetree.FileTreeTab
import com.rk.resources.drawables
import com.rk.utils.toast
import kotlinx.coroutines.launch

/** The AI Agent service tab (sits above Git in the drawer rail). */
class AiTab : DrawerTab() {
    override fun getName(): String = "AI"

    override fun getIcon(): com.rk.icons.Icon = com.rk.icons.Icon.ResourceIcon(drawables.bolt)

    /** Hidden when the user turns the AI Agent feature off in Settings → App. */
    override fun isSupported(): Boolean = com.rk.settings.app.InbuiltFeatures.ai.state.value

    @Composable
    override fun Content(modifier: Modifier) {
        AiScreen(modifier)
    }
}

@Composable
private fun AiScreen(modifier: Modifier) {
    val vm: AiViewModel = viewModel()
    val activity = LocalActivity.current as MainActivity
    val drawerVm = activity.drawerViewModel

    val workingDir =
        remember(drawerVm.currentDrawerTab) {
            (drawerVm.currentDrawerTab as? FileTreeTab)?.root?.getAbsolutePath() ?: sandboxHomeDir().absolutePath
        }

    var showAddKey by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }

    // Auto-maximize the drawer for the chat, and ensure a session exists.
    LaunchedEffect(Unit) {
        isDrawerExpanded = true
        if (vm.sessions.isEmpty()) vm.newSession()
        if (AiPrefs.hasKey(vm.providerId) && vm.models.isEmpty()) vm.refreshModels()
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (!AiPrefs.hasKey(vm.providerId)) {
            SetupPrompt(onAdd = { showAddKey = true })
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar: sessions + title + new chat.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { showSessions = true }) {
                        Icon(painterResource(drawables.command_palette), contentDescription = "Chats")
                    }
                    Text(
                        text = vm.current?.title ?: "AI",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showAddKey = true }) {
                        Icon(painterResource(drawables.settings), contentDescription = "AI settings")
                    }
                    IconButton(onClick = { vm.newSession() }) {
                        Icon(painterResource(drawables.add), contentDescription = "New chat")
                    }
                }

                if (AiTaskStore.tasks.isNotEmpty()) {
                    Surface(
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                            Text("Tasks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            AiTaskStore.tasks.forEach { t ->
                                Text(
                                    text = (if (t.done) "✓ " else "• ") + t.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color =
                                        if (t.done) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }

                if (AiTerminal.isAlive) {
                    Surface(
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painterResource(drawables.terminal),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = AiTerminal.currentCommand?.let { "Terminal · $it" } ?: "Agent terminal running",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { AiTerminal.kill() }) { Text("Kill") }
                        }
                    }
                }

                MessagesList(vm = vm, workingDir = workingDir, modifier = Modifier.weight(1f).fillMaxWidth())

                vm.error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }

                Composer(vm = vm, workingDir = workingDir)
            }
        }
    }

    if (showAddKey) AddKeyDialog(vm = vm, onDismiss = { showAddKey = false })
    if (showSessions) SessionsDialog(vm = vm, onDismiss = { showSessions = false })
    vm.pendingPermission?.let { req -> PermissionDialog(req = req, vm = vm) }
}

@Composable
private fun SetupPrompt(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(drawables.bolt), contentDescription = null, modifier = Modifier.size(40.dp))
        Spacer(Modifier.size(12.dp))
        Text("AI Agent", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(8.dp))
        Text(
            "Add an API key (OpenAI, Gemini, OpenRouter, or a custom OpenAI-compatible endpoint) to start chatting and let the agent use tools.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(16.dp))
        TextButton(onClick = onAdd) { Text("Add API key") }
    }
}

@Composable
private fun MessagesList(vm: AiViewModel, workingDir: String, modifier: Modifier) {
    val listState = rememberLazyListState()
    val messages = vm.current?.messages ?: return
    var editing by remember { mutableStateOf<AiMessage?>(null) }
    LaunchedEffect(messages.size, vm.busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LazyColumn(
        modifier = modifier.padding(horizontal = 8.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = messages.filter { it.role != "system" }, key = { System.identityHashCode(it) }) { msg ->
            MessageItem(msg, onEditUser = { if (!vm.busy) editing = it })
        }
        if (vm.busy) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Thinking…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    editing?.let { msg ->
        var draft by remember(msg) { mutableStateOf(msg.content) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit & resend") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 8,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        val m = msg
                        editing = null
                        vm.editAndResend(m, draft.trim(), workingDir)
                    },
                ) { Text("Resend") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MessageItem(msg: AiMessage, onEditUser: (AiMessage) -> Unit = {}) {
    when (msg.role) {
        "user" ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(0.85f).clickable { onEditUser(msg) },
                ) {
                    Text(msg.content, modifier = Modifier.padding(10.dp))
                }
            }
        "tool" ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        text = "⮑ " + msg.content.take(2000),
                        modifier = Modifier.padding(10.dp).heightIn(max = 220.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        else -> // assistant
        Column(modifier = Modifier.fillMaxWidth()) {
            if (msg.content.isNotBlank()) {
                AssistantContent(msg.content)
            }
            msg.toolCalls.forEach { call ->
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(drawables.bolt),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        AiTools.describe(call),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantContent(content: String) {
    val parts = content.split("```")
    Column(modifier = Modifier.fillMaxWidth()) {
        parts.forEachIndexed { i, part ->
            if (part.isBlank()) return@forEachIndexed
            if (i % 2 == 1) {
                // Code block; drop an optional leading language tag line.
                val body = if (part.contains('\n')) part.substringAfter('\n') else part
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                onClick = {
                                    ClipboardUtils.copyText("Code", body.trimEnd())
                                    toast("Copied")
                                }
                            ) {
                                Icon(painterResource(drawables.copy), contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy")
                            }
                        }
                        SelectionContainer {
                            Text(
                                text = body.trimEnd(),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).heightIn(max = 300.dp),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.size(6.dp))
                    }
                }
            } else {
                MarkdownText(part.trim())
            }
        }
    }
}

/** Lightweight markdown renderer (headings, bold/italic, inline code, bullet & numbered lists). */
@Composable
private fun MarkdownText(text: String) {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier = Modifier.fillMaxWidth()) {
        text.split("\n").forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.size(4.dp))
                line.startsWith("### ") ->
                    SelectionContainer {
                        Text(parseInline(line.removePrefix("### "), codeBg), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                line.startsWith("## ") ->
                    SelectionContainer {
                        Text(parseInline(line.removePrefix("## "), codeBg), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                line.startsWith("# ") ->
                    SelectionContainer {
                        Text(parseInline(line.removePrefix("# "), codeBg), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                line.startsWith("> ") ->
                    SelectionContainer {
                        Text(
                            parseInline(line.removePrefix("> "), codeBg),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                line.startsWith("- ") || line.startsWith("* ") ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("•  ")
                        SelectionContainer { Text(parseInline(line.drop(2), codeBg)) }
                    }
                Regex("^\\d+\\.\\s").containsMatchIn(line) -> {
                    val num = line.substringBefore('.', "")
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("$num.  ")
                        SelectionContainer { Text(parseInline(line.substringAfter(". ", line), codeBg)) }
                    }
                }
                else -> SelectionContainer { Text(parseInline(line, codeBg)) }
            }
        }
    }
}

/** Parse inline markdown spans: **bold**/__bold__, *italic*, `code`. */
private fun parseInline(s: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end >= 0) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(s.substring(i + 2, end)); pop(); i = end + 2
                } else { append(s[i]); i++ }
            }
            s.startsWith("__", i) -> {
                val end = s.indexOf("__", i + 2)
                if (end >= 0) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(s.substring(i + 2, end)); pop(); i = end + 2
                } else { append(s[i]); i++ }
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end >= 0) {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)); append(s.substring(i + 1, end)); pop(); i = end + 1
                } else { append(s[i]); i++ }
            }
            s[i] == '*' -> {
                val end = s.indexOf('*', i + 1)
                if (end > i + 1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); append(s.substring(i + 1, end)); pop(); i = end + 1
                } else { append(s[i]); i++ }
            }
            else -> { append(s[i]); i++ }
        }
    }
}

@Composable
private fun Composer(vm: AiViewModel, workingDir: String) {
    var text by remember { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val slashCommands =
        remember {
            listOf(
                "/new" to "Start a new chat",
                "/clear" to "Clear this chat",
                "/model" to "Choose the model",
                "/stop" to "Stop the running turn",
                "/copy" to "Copy the last reply",
                "/retry" to "Resend the last message",
                "/help" to "Show all commands",
            )
        }
    val runSlash: (String) -> Unit = { raw ->
        when (raw.trim().substringBefore(' ').lowercase()) {
            "/new" -> vm.newSession()
            "/clear" -> vm.clearCurrent()
            "/model" -> {
                modelMenu = true
                if (vm.models.isEmpty()) vm.refreshModels()
            }
            "/stop" -> vm.stop()
            "/copy" -> {
                ClipboardUtils.copyText("AI", vm.lastAssistant())
                toast("Copied")
            }
            "/retry" -> {
                val t = vm.lastUserText()
                if (t.isNotBlank()) vm.send(t, workingDir) else toast("Nothing to retry")
            }
            "/help" -> showHelp = true
            else -> toast("Unknown command")
        }
    }

    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            // Slash-command suggestions (shown while typing "/…").
            if (text.startsWith("/")) {
                val q = text.trim().lowercase()
                Column(modifier = Modifier.fillMaxWidth()) {
                    slashCommands
                        .filter { it.first.startsWith(q) || q == "/" }
                        .forEach { (name, desc) ->
                            TextButton(
                                onClick = {
                                    text = ""
                                    runSlash(name)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                }
            }
            // Directory + model + %used row.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "📁 " + workingDir.substringAfterLast('/'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    TextButton(onClick = { modelMenu = true; if (vm.models.isEmpty()) vm.refreshModels() }) {
                        Text(
                            text = vm.model.ifBlank { "Select model" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(140.dp),
                        )
                        Icon(painterResource(drawables.chevron_down), contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        if (vm.loadingModels) {
                            DropdownMenuItem(text = { Text("Loading…") }, onClick = {})
                        }
                        vm.models.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m) },
                                onClick = {
                                    vm.selectModel(m)
                                    modelMenu = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${vm.percentUsed}% used",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask the agent…") },
                    enabled = !vm.busy,
                    maxLines = 6,
                )
                Spacer(Modifier.width(8.dp))
                if (vm.busy) {
                    IconButton(onClick = { vm.stop() }) {
                        Icon(painterResource(drawables.stop), contentDescription = "Stop")
                    }
                } else {
                    IconButton(
                        enabled = text.isNotBlank(),
                        onClick = {
                            val t = text.trim()
                            text = ""
                            if (t.startsWith("/")) runSlash(t) else vm.send(t, workingDir)
                        },
                    ) {
                        Icon(painterResource(drawables.send), contentDescription = "Send")
                    }
                }
            }

            if (showHelp) {
                AlertDialog(
                    onDismissRequest = { showHelp = false },
                    title = { Text("Chat commands") },
                    text = {
                        Column {
                            slashCommands.forEach { (name, desc) ->
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(8.dp))
                                    Text(desc, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Close") } },
                )
            }
        }
    }
}

@Composable
private fun PermissionDialog(req: AiViewModel.PermissionRequest, vm: AiViewModel) {
    var rememberChoice by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Allow tool: ${req.toolName}?") },
        text = {
            Column {
                Text(req.description, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                    Spacer(Modifier.width(6.dp))
                    Text("Don't ask again for this tool", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.resolvePermission(true, rememberChoice) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { vm.resolvePermission(false, rememberChoice) }) { Text("Deny") } },
    )
}

@Composable
private fun SessionsDialog(vm: AiViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chats") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (vm.sessions.isEmpty()) Text("No chats yet.")
                vm.sessions.forEach { s ->
                    TextButton(
                        onClick = {
                            vm.selectSession(s.id)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            s.title,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (s.id == vm.currentSessionId) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.newSession(); onDismiss() }) { Text("New chat") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun AddKeyDialog(vm: AiViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var providerId by remember { mutableStateOf(vm.providerId) }
    var providerMenu by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(AiPrefs.getBaseUrl(vm.providerId)) }
    var key by remember { mutableStateOf(AiPrefs.getKey(providerId)) }
    var verifying by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val provider = AiProviders.byId(providerId)

    AlertDialog(
        onDismissRequest = { if (!verifying) onDismiss() },
        title = { Text("AI provider & key") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box {
                    TextButton(onClick = { providerMenu = true }) {
                        Text(provider.label)
                        Icon(painterResource(drawables.chevron_down), contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                        AiProviders.all.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.label) },
                                onClick = {
                                    providerId = p.id
                                    key = AiPrefs.getKey(p.id)
                                    baseUrl = AiPrefs.getBaseUrl(p.id)
                                    providerMenu = false
                                },
                            )
                        }
                    }
                }
                if (provider.editableBaseUrl) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL (…/v1)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(8.dp))
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                status?.let {
                    Spacer(Modifier.size(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (provider.signupUrl.isNotBlank()) {
                    Spacer(Modifier.size(6.dp))
                    Text("Get a key: ${provider.signupUrl}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !verifying && key.isNotBlank(),
                onClick = {
                    verifying = true
                    status = null
                    if (provider.editableBaseUrl) AiPrefs.setBaseUrl(provider.id, baseUrl)
                    scope.launch {
                        runCatching { AiClient.listModels(provider, key.trim()) }
                            .onSuccess { models ->
                                AiPrefs.setKey(provider.id, key)
                                vm.selectProvider(provider.id)
                                if (models.isNotEmpty()) vm.selectModel(models.first())
                                vm.refreshModels()
                                verifying = false
                                onDismiss()
                            }
                            .onFailure {
                                verifying = false
                                status = "Verification failed: ${it.message}"
                            }
                    }
                },
            ) {
                Text(if (verifying) "Verifying…" else "Verify & save")
            }
        },
        dismissButton = { TextButton(enabled = !verifying, onClick = onDismiss) { Text("Cancel") } },
    )
}
