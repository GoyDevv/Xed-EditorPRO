package com.rk.git

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.rk.activities.main.MainActivity
import com.rk.activities.main.fileTreeViewModel
import com.rk.activities.settings.SettingsActivity
import com.rk.activities.settings.SettingsRoutes
import com.rk.components.SingleInputDialog
import com.rk.components.compose.utils.addIf
import com.rk.components.getDrawerWidth
import com.rk.drawer.DrawerTab
import com.rk.file.toFileWrapper
import com.rk.filetree.FileNameIcon
import com.rk.filetree.FileTreeTab
import com.rk.icons.Icon
import com.rk.resources.drawables
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.app.InbuiltFeatures
import com.rk.tabs.editor.EditorTab
import com.rk.utils.drawErrorUnderline
import com.rk.utils.findGitRoot
import com.rk.utils.getGitColor
import com.rk.utils.getUnderlineColor
import com.rk.utils.toast
import java.io.File
import kotlinx.coroutines.launch
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

/** Convert a git remote URL (ssh or https) to its https web base, e.g. https://github.com/owner/repo. */
private fun remoteToWebUrl(remote: String?): String? {
    if (remote.isNullOrBlank()) return null
    var u = remote.trim().removeSuffix(".git")
    return when {
        u.startsWith("git@") -> "https://" + u.removePrefix("git@").replaceFirst(":", "/")
        u.startsWith("http") -> u
        else -> null
    }
}

class GitTab(val viewModel: GitViewModel) : DrawerTab() {

    @Composable
    override fun Content(modifier: Modifier) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val interactionSource = remember { MutableInteractionSource() }

        var showBranchesMenu by remember { mutableStateOf(false) }
        var showNewBranchDialog by remember { mutableStateOf(false) }
        var showPushConfirmDialog by remember { mutableStateOf(false) }
        var showOverflow by remember { mutableStateOf(false) }
        var showOriginDialog by remember { mutableStateOf(false) }
        var showPrDialog by remember { mutableStateOf(false) }
        var force by remember { mutableStateOf(false) }
        var newBranch by remember { mutableStateOf("") }
        var newBranchError by remember { mutableStateOf<String?>(null) }

        // 0 = Code, 1 = Diff, 2 = Actions
        var section by remember { mutableStateOf(0) }
        var expandedDiffPaths by remember { mutableStateOf(setOf<String>()) }

        val gitChanges = viewModel.currentRoot.value?.absolutePath?.let { viewModel.changes[it] } ?: emptyList()
        val hasCheckedChanges by remember(gitChanges) { derivedStateOf { gitChanges.any { it.isChecked } } }

        val commitTitle = viewModel.currentRoot.value?.absolutePath?.let { viewModel.commitMessages[it] } ?: ""
        val commitDescription = viewModel.currentRoot.value?.absolutePath?.let { viewModel.commitDescriptions[it] } ?: ""
        val amend = viewModel.currentRoot.value?.absolutePath?.let { viewModel.amends[it] } ?: false

        suspend fun refreshEditors() {
            MainActivity.instance?.viewModel?.tabs?.filterIsInstance<EditorTab>()?.forEach {
                if (findGitRoot(it.file.getAbsolutePath()) != null) it.refresh()
            }
        }

        fun openFileInDiff(change: GitChange) {
            expandedDiffPaths = setOf(change.path)
            viewModel.loadDiffFor(change)
            section = 1
        }

        Column(modifier = modifier.fillMaxSize()) {
            // --- Top bar: branch selector + pull/fetch/push + overflow ---
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    TextButton(onClick = { showBranchesMenu = true }, enabled = !viewModel.isLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.wrapContentWidth()) {
                            Icon(painterResource(drawables.branch), contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                viewModel.currentBranch,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.size(4.dp))
                            Icon(painterResource(drawables.chevron_down), contentDescription = null)
                        }
                    }
                    DropdownMenu(expanded = showBranchesMenu, onDismissRequest = { showBranchesMenu = false }) {
                        viewModel.getBranchList().forEach { branch ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = branch == viewModel.currentBranch, onClick = null)
                                        Spacer(Modifier.width(12.dp))
                                        Text(branch)
                                    }
                                },
                                onClick = {
                                    viewModel.checkout(branch)
                                    showBranchesMenu = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Outlined.Add, contentDescription = null)
                                    Spacer(Modifier.width(12.dp))
                                    Text(stringResource(strings.new_branch))
                                }
                            },
                            onClick = {
                                showBranchesMenu = false
                                showNewBranchDialog = true
                            },
                        )
                    }
                }

                IconButton(
                    onClick = { scope.launch { viewModel.pull().join(); refreshEditors() } },
                    enabled = !viewModel.isLoading,
                ) {
                    Icon(painterResource(drawables.pull), contentDescription = stringResource(strings.pull))
                }
                IconButton(onClick = { viewModel.fetch() }, enabled = !viewModel.isLoading) {
                    Icon(painterResource(drawables.fetch), contentDescription = stringResource(strings.fetch))
                }
                IconButton(onClick = { showPushConfirmDialog = true }, enabled = !viewModel.isLoading) {
                    Icon(painterResource(drawables.push), contentDescription = stringResource(strings.push))
                }
                Box {
                    IconButton(onClick = { showOverflow = true }) {
                        Icon(imageVector = Icons.Outlined.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        DropdownMenuItem(
                            text = { Text("Origin manager") },
                            onClick = {
                                showOverflow = false
                                showOriginDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Create pull request") },
                            onClick = {
                                showOverflow = false
                                showPrDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Git settings") },
                            onClick = {
                                showOverflow = false
                                context.startActivity(
                                    Intent(context, SettingsActivity::class.java)
                                        .putExtra("route", SettingsRoutes.Git.route)
                                )
                            },
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
                if (viewModel.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxSize())
                else HorizontalDivider()
            }

            // --- Section tabs ---
            TabRow(selectedTabIndex = section) {
                listOf("Code", "Diff", "Actions").forEachIndexed { index, title ->
                    Tab(
                        selected = section == index,
                        onClick = { section = index },
                        text = { Text(title, maxLines = 1) },
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = section,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally { it * dir } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it * dir } + fadeOut())
                    },
                    label = "gitSection",
                ) { current ->
                    when (current) {
                        0 ->
                            CodeSection(
                                gitChanges = gitChanges,
                                hasCheckedChanges = hasCheckedChanges,
                                commitTitle = commitTitle,
                                commitDescription = commitDescription,
                                amend = amend,
                                interactionSource = interactionSource,
                                onOpenFileDiff = { openFileInDiff(it) },
                                onCommit = { viewModel.commit() },
                                onCommitAndPush = {
                                    scope.launch {
                                        viewModel.commit().join()
                                        showPushConfirmDialog = true
                                    }
                                },
                            )
                        1 ->
                            DiffSection(
                                gitChanges = gitChanges,
                                expandedPaths = expandedDiffPaths,
                                onToggle = { change ->
                                    expandedDiffPaths =
                                        if (expandedDiffPaths.contains(change.path)) expandedDiffPaths - change.path
                                        else {
                                            viewModel.loadDiffFor(change)
                                            expandedDiffPaths + change.path
                                        }
                                },
                            )
                        else -> ActionsSection(context)
                    }
                }
            }
        }

        // --- Dialogs ---
        if (showNewBranchDialog) {
            SingleInputDialog(
                title = stringResource(id = strings.new_branch),
                inputLabel = stringResource(id = strings.new_branch_label, viewModel.currentBranch),
                inputValue = newBranch,
                errorMessage = newBranchError,
                confirmText = stringResource(strings.ok),
                onInputValueChange = {
                    newBranch = it
                    newBranchError = if (newBranch.isBlank()) strings.value_empty_err.getString() else null
                },
                onConfirm = { viewModel.checkoutNew(newBranch, viewModel.currentBranch) },
                onFinish = {
                    newBranch = ""
                    newBranchError = null
                    showNewBranchDialog = false
                },
                confirmEnabled = newBranchError == null && newBranch.isNotBlank(),
            )
        }

        if (showPushConfirmDialog) {
            val commitCount = viewModel.getCommitCount()
            AlertDialog(
                onDismissRequest = {
                    showPushConfirmDialog = false
                    force = false
                },
                title = { Text(stringResource(strings.push)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                if (commitCount > 0) strings.push_dialog_message_commits
                                else strings.push_dialog_message_empty,
                                commitCount,
                                viewModel.currentBranch,
                            )
                        )
                        if (force) {
                            Text(
                                "⚠ Force push overwrites the remote branch and can erase others' commits. Use with care.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (commitCount > 0) {
                            Row(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .height(40.dp)
                                        .toggleable(
                                            value = force,
                                            onValueChange = { force = it },
                                            role = Role.Checkbox,
                                            indication = null,
                                            interactionSource = interactionSource,
                                        )
                                        .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = force, interactionSource = interactionSource, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(strings.push_dialog_checkbox_force))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = commitCount > 0,
                        onClick = {
                            showPushConfirmDialog = false
                            viewModel.push(force)
                            force = false
                        },
                    ) {
                        Text(stringResource(strings.push))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showPushConfirmDialog = false
                            force = false
                        }
                    ) {
                        Text(stringResource(strings.cancel))
                    }
                },
            )
        }

        if (showOriginDialog) {
            OriginManagerDialog(context = context, onDismiss = { showOriginDialog = false })
        }

        if (showPrDialog) {
            PullRequestDialog(context = context, onDismiss = { showPrDialog = false })
        }

        viewModel.discardTarget?.let { change ->
            AlertDialog(
                onDismissRequest = { viewModel.discardTarget = null },
                title = { Text(stringResource(strings.discard_changes)) },
                text = {
                    Text(stringResource(strings.discard_changes_message) + "\n\n" + change.path.substringAfterLast("/"))
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.discardChanges(change) }) {
                        Text(stringResource(strings.discard_changes))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.discardTarget = null }) { Text(stringResource(strings.cancel)) }
                },
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // CODE section: change lists + commit box
    // ---------------------------------------------------------------------------------------------
    @Composable
    private fun CodeSection(
        gitChanges: List<GitChange>,
        hasCheckedChanges: Boolean,
        commitTitle: String,
        commitDescription: String,
        amend: Boolean,
        interactionSource: MutableInteractionSource,
        onOpenFileDiff: (GitChange) -> Unit,
        onCommit: () -> Unit,
        onCommitAndPush: () -> Unit,
    ) {
        var changesExpanded by remember { mutableStateOf(true) }
        var untrackedExpanded by remember { mutableStateOf(true) }
        var conflictsExpanded by remember { mutableStateOf(true) }

        val changes = gitChanges.filter { it.type == ChangeType.ADDED || it.type == ChangeType.MODIFIED || it.type == ChangeType.DELETED }
        val conflicts = gitChanges.filter { it.type == ChangeType.CONFLICTING }
        val untracked = gitChanges.filter { it.type == ChangeType.UNTRACKED }

        Column(modifier = Modifier.fillMaxSize()) {
            if (gitChanges.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = true),
                    state = rememberLazyListState(),
                    contentPadding = PaddingValues(top = 8.dp),
                ) {
                    item { ConflictsList(conflicts, conflictsExpanded, onOpenFileDiff) { conflictsExpanded = !conflictsExpanded } }
                    item { ChangesList(changes, changesExpanded, onOpenFileDiff) { changesExpanded = !changesExpanded } }
                    item { UntrackedList(untracked, untrackedExpanded, onOpenFileDiff) { untrackedExpanded = !untrackedExpanded } }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        painter = painterResource(drawables.git),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(strings.no_changes), style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(strings.no_changes_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(40.dp)
                        .toggleable(
                            value = amend,
                            enabled = !viewModel.isLoading,
                            onValueChange = { viewModel.toggleAmend(it) },
                            role = Role.Checkbox,
                            indication = null,
                            interactionSource = interactionSource,
                        )
                        .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = amend, enabled = !viewModel.isLoading, interactionSource = interactionSource, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(strings.amend))
            }

            OutlinedTextField(
                enabled = !viewModel.isLoading,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                value = commitTitle,
                onValueChange = { viewModel.changeCommitMessage(it) },
                singleLine = true,
                label = { Text("Commit title") },
                placeholder = { Text(stringResource(strings.commit_message)) },
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                enabled = !viewModel.isLoading,
                modifier = Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 8.dp),
                value = commitDescription,
                onValueChange = { viewModel.changeCommitDescription(it) },
                label = { Text("Description (optional)") },
            )

            Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Button(
                    enabled = !viewModel.isLoading && commitTitle.isNotBlank() && hasCheckedChanges,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCommit,
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(painterResource(drawables.commit), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(if (amend) strings.amend_commit else strings.commit), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    enabled = !viewModel.isLoading && commitTitle.isNotBlank() && hasCheckedChanges,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCommitAndPush,
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(painterResource(drawables.push), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(
                        stringResource(if (amend) strings.amend_commit_and_push else strings.commit_and_push),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // DIFF section: collapsible per-file cards with colored +/- lines and counts
    // ---------------------------------------------------------------------------------------------
    @Composable
    private fun DiffSection(gitChanges: List<GitChange>, expandedPaths: Set<String>, onToggle: (GitChange) -> Unit) {
        if (gitChanges.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(strings.no_changes), style = MaterialTheme.typography.titleSmall)
            }
            return
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
            items(gitChanges.size) { i ->
                val change = gitChanges[i]
                DiffCard(change = change, expanded = expandedPaths.contains(change.path), onToggle = { onToggle(change) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    @Composable
    private fun DiffCard(change: GitChange, expanded: Boolean, onToggle: () -> Unit) {
        val diff = viewModel.diffs[change.path]
        val (added, removed) = remember(diff) { diffStats(diff) }
        val rotation by animateFloatAsState(targetValue = if (expanded) 90f else 0f, label = "diffRotate")

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onToggle).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(drawables.chevron_right),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).rotate(rotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    FileNameIcon(fileName = change.path.substringAfterLast("/"), isDirectory = false)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        change.path.substringAfterLast("/"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = getGitColor(change.type),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (diff != null) {
                        Text("+$added", color = Color(0xFF4CAF50), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(6.dp))
                        Text("-$removed", color = Color(0xFFE53935), style = MaterialTheme.typography.labelMedium)
                    }
                }
                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        HorizontalDivider()
                        if (diff == null) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(stringResource(strings.loading_diff), style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            DiffBody(diff)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DiffBody(diff: String) {
        val scheme = MaterialTheme.colorScheme
        val addedBg = Color(0xFF4CAF50).copy(alpha = 0.14f)
        val removedBg = Color(0xFFE53935).copy(alpha = 0.14f)
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp)
        ) {
            diff.split("\n").forEach { line ->
                val bg =
                    when {
                        line.startsWith("+") && !line.startsWith("+++") -> addedBg
                        line.startsWith("-") && !line.startsWith("---") -> removedBg
                        else -> Color.Transparent
                    }
                val color =
                    when {
                        line.startsWith("+++") || line.startsWith("---") -> scheme.onSurfaceVariant
                        line.startsWith("+") -> Color(0xFF2E7D32)
                        line.startsWith("-") -> Color(0xFFC62828)
                        line.startsWith("@@") -> scheme.primary
                        line.startsWith("diff ") || line.startsWith("index ") -> scheme.onSurfaceVariant
                        else -> scheme.onSurface
                    }
                Text(
                    text = if (line.isEmpty()) " " else line,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 10.dp),
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ACTIONS section (CI runs). Full API-backed live logs are the next stage; this links out.
    // ---------------------------------------------------------------------------------------------
    @Composable
    private fun ActionsSection(context: android.content.Context) {
        val origin = remember { viewModel.getOriginUrl() }
        val web = remember(origin) { remoteToWebUrl(origin) }
        val ownerRepo = remember(origin) { GitHubCli.parseOwnerRepo(origin) }

        var runs by remember { mutableStateOf<List<GitHubCli.Run>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var refreshKey by remember { mutableStateOf(0) }
        var tapped by remember { mutableStateOf<GitHubCli.Run?>(null) }

        LaunchedEffect(refreshKey) {
            if (GitHubCli.hasToken() && ownerRepo != null) {
                loading = true
                error = null
                GitHubCli.listRuns(ownerRepo.first, ownerRepo.second)
                    .onSuccess { runs = it }
                    .onFailure { error = it.message }
                loading = false
            }
        }

        if (!GitHubCli.hasToken() || ownerRepo == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(painterResource(drawables.run), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text("CI Actions", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (ownerRepo == null) "This works with a GitHub remote. Set the origin to a github.com repository."
                    else "Add your GitHub token in Git settings (the password field = a personal access token) to see workflow runs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (web != null) {
                    Button(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "$web/actions".toUri())) } }) {
                        Text("Open Actions on GitHub")
                    }
                }
                TextButton(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java).putExtra("route", SettingsRoutes.Git.route)) }) {
                    Text("Open Git settings")
                }
            }
            return
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Workflow runs", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (loading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                IconButton(onClick = { refreshKey++ }) { Icon(painterResource(drawables.refresh), contentDescription = "Refresh") }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (runs.isEmpty() && !loading) {
                    item { Text("No workflow runs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                }
                items(runs.size) { i ->
                    val r = runs[i]
                    val (dot, label) = runStatus(r)
                    Row(
                        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { tapped = r }).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.size(10.dp).background(dot, RoundedCornerShape(50)))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(r.title.ifBlank { "(run)" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${r.branch} · $label", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(painterResource(drawables.chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                    HorizontalDivider()
                }
            }
        }

        tapped?.let { run ->
            val running = run.status != "completed"
            AlertDialog(
                onDismissRequest = { tapped = null },
                title = { Text(if (running) "Run in progress" else "Workflow run") },
                text = {
                    Text(
                        (if (running) "This run is still ${run.status}. " else "Result: ${run.conclusion.ifBlank { "unknown" }}. ") +
                            "Open it on GitHub to see the full, live log?"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        tapped = null
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, run.htmlUrl.toUri())) }
                    }) { Text("Open on GitHub") }
                },
                dismissButton = { TextButton(onClick = { tapped = null }) { Text(stringResource(strings.cancel)) } },
            )
        }
    }

    private fun runStatus(r: GitHubCli.Run): Pair<Color, String> =
        when {
            r.status != "completed" -> Color(0xFFFFB300) to (r.status.ifBlank { "queued" })
            r.conclusion == "success" -> Color(0xFF4CAF50) to "success"
            r.conclusion == "failure" -> Color(0xFFE53935) to "failure"
            r.conclusion == "cancelled" -> Color(0xFF9E9E9E) to "cancelled"
            else -> Color(0xFF9E9E9E) to r.conclusion.ifBlank { "done" }
        }

    @Composable
    private fun OriginManagerDialog(context: android.content.Context, onDismiss: () -> Unit) {
        val scope = rememberCoroutineScope()
        val current = remember { viewModel.getOriginUrl() ?: "" }
        var url by remember { mutableStateOf(current) }
        var newName by remember { mutableStateOf("") }
        var newPrivate by remember { mutableStateOf(true) }
        var busy by remember { mutableStateOf(false) }
        var repos by remember { mutableStateOf<List<GitHubCli.Repo>>(emptyList()) }
        var query by remember { mutableStateOf("") }
        var loadingRepos by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = { Text("Origin manager") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text("Remote origin", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Origin URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = url.isNotBlank(), onClick = { viewModel.setOriginUrl(url) }) { Text("Save URL") }

                    HorizontalDivider(); Spacer(Modifier.height(8.dp))

                    Text("Create new repository", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Repository name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = newPrivate, onCheckedChange = { newPrivate = it })
                        Text("Private")
                    }
                    Button(
                        enabled = !busy && newName.isNotBlank() && GitHubCli.hasToken(),
                        onClick = {
                            busy = true
                            scope.launch {
                                GitHubCli.createRepo(newName.trim(), newPrivate)
                                    .onSuccess { cloneUrl ->
                                        viewModel.setOriginUrl(cloneUrl)
                                        url = cloneUrl
                                        toast("Repository created")
                                    }
                                    .onFailure { toast(it.message) }
                                busy = false
                            }
                        },
                    ) {
                        Text(if (busy) "Working…" else "Create & set as origin")
                    }
                    if (!GitHubCli.hasToken()) {
                        Text(
                            "Add your GitHub token in Git settings (password field = a personal access token) to create/search repositories.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    HorizontalDivider(); Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Your repositories", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (loadingRepos) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        TextButton(
                            enabled = !loadingRepos && GitHubCli.hasToken(),
                            onClick = {
                                loadingRepos = true
                                scope.launch {
                                    GitHubCli.listRepos().onSuccess { repos = it }.onFailure { toast(it.message) }
                                    loadingRepos = false
                                }
                            },
                        ) {
                            Text("Load")
                        }
                    }
                    if (repos.isNotEmpty()) {
                        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        repos.filter { it.fullName.contains(query, ignoreCase = true) }.take(40).forEach { repo ->
                            TextButton(
                                onClick = {
                                    viewModel.setOriginUrl(repo.cloneUrl)
                                    url = repo.cloneUrl
                                    toast("Origin set to ${repo.fullName}")
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(repo.fullName, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(strings.close)) } },
        )
    }

    @Composable
    private fun PullRequestDialog(context: android.content.Context, onDismiss: () -> Unit) {
        val scope = rememberCoroutineScope()
        val ownerRepo = remember { GitHubCli.parseOwnerRepo(viewModel.getOriginUrl()) }
        val head = viewModel.currentBranch
        var base by remember { mutableStateOf("main") }
        var title by remember {
            mutableStateOf(viewModel.currentRoot.value?.absolutePath?.let { viewModel.commitMessages[it] } ?: "")
        }
        var body by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val ready = ownerRepo != null && GitHubCli.hasToken()

        AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = { Text("Create pull request") },
            text = {
                Column {
                    if (!ready) {
                        Text("This needs a GitHub remote and your token (set it in Git settings).")
                    } else {
                        Text("From \"$head\" into:", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = base, onValueChange = { base = it }, label = { Text("Base branch") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth().height(96.dp))
                        Text(
                            "Note: push your branch first — GitHub needs the branch on the remote to open a PR.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && ready && title.isNotBlank() && base.isNotBlank(),
                    onClick = {
                        busy = true
                        scope.launch {
                            GitHubCli.createPr(ownerRepo!!.first, ownerRepo.second, head, base.trim(), title.trim(), body)
                                .onSuccess { urlOrMsg ->
                                    toast("Pull request created")
                                    if (urlOrMsg.startsWith("http")) {
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, urlOrMsg.toUri())) }
                                    }
                                    onDismiss()
                                }
                                .onFailure { toast(it.message) }
                            busy = false
                        }
                    },
                ) {
                    Text(if (busy) "Creating…" else "Create")
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(strings.cancel)) } },
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Change lists (Code section)
    // ---------------------------------------------------------------------------------------------
    @Composable
    private fun ColumnScope.ConflictsList(
        conflicts: List<GitChange>,
        expanded: Boolean,
        onOpenFileDiff: (GitChange) -> Unit,
        onToggleExpansion: () -> Unit,
    ) = ChangeGroup(strings.conflicts.getString(), conflicts, expanded, onOpenFileDiff, onToggleExpansion)

    @Composable
    private fun ColumnScope.ChangesList(
        changes: List<GitChange>,
        expanded: Boolean,
        onOpenFileDiff: (GitChange) -> Unit,
        onToggleExpansion: () -> Unit,
    ) = ChangeGroup(strings.changes.getString(), changes, expanded, onOpenFileDiff, onToggleExpansion)

    @Composable
    private fun ColumnScope.UntrackedList(
        untracked: List<GitChange>,
        expanded: Boolean,
        onOpenFileDiff: (GitChange) -> Unit,
        onToggleExpansion: () -> Unit,
    ) = ChangeGroup(strings.untracked.getString(), untracked, expanded, onOpenFileDiff, onToggleExpansion)

    @Composable
    private fun ColumnScope.ChangeGroup(
        label: String,
        items: List<GitChange>,
        expanded: Boolean,
        onOpenFileDiff: (GitChange) -> Unit,
        onToggleExpansion: () -> Unit,
    ) {
        if (items.isEmpty()) return

        val selectionState =
            when {
                items.all { it.isChecked } -> ToggleableState.On
                items.none { it.isChecked } -> ToggleableState.Off
                else -> ToggleableState.Indeterminate
            }

        Row(
            modifier = Modifier.width(getDrawerWidth() - 61.dp).combinedClickable(onClick = onToggleExpansion).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val rotationDegree by animateFloatAsState(targetValue = if (!expanded) 0f else 90f, label = "rotation")
            Icon(
                painterResource(drawables.chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp).rotate(rotationDegree),
            )
            Spacer(Modifier.width(4.dp))
            TriStateCheckbox(
                enabled = !viewModel.isLoading,
                state = selectionState,
                onClick = {
                    if (selectionState == ToggleableState.On) items.forEach { viewModel.removeChange(it) }
                    else items.forEach { viewModel.addChange(it) }
                },
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text(
                text = items.size.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }

        AnimatedVisibility(visible = expanded) { ChangesItemList(items, onOpenFileDiff) }
        Spacer(modifier = Modifier.height(8.dp))
    }

    @Composable
    private fun ChangesItemList(items: List<GitChange>, onOpenFileDiff: (GitChange) -> Unit) {
        val context = LocalContext.current
        Column(modifier = Modifier.padding(start = 40.dp)) {
            items.forEach { change ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.width(getDrawerWidth() - 61.dp)
                            .combinedClickable(
                                onClick = { onOpenFileDiff(change) },
                                onLongClick = { viewModel.discardTarget = change },
                            )
                            .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        enabled = !viewModel.isLoading,
                        checked = change.isChecked,
                        onCheckedChange = { viewModel.toggleChange(change) },
                        modifier = Modifier.size(20.dp),
                    )
                    val file = File(change.absolutePath).toFileWrapper()
                    val fileName = change.path.substringAfterLast("/")
                    FileNameIcon(fileName = fileName, isDirectory = false)
                    val underlineColor = fileTreeViewModel.get()?.let { getUnderlineColor(context, it, file) }
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = getGitColor(change.type),
                        modifier = Modifier.addIf(underlineColor != null) { drawErrorUnderline(underlineColor!!) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = change.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    override fun getName(): String = strings.git.getString()

    override fun getIcon(): Icon = Icon.ResourceIcon(drawables.git)

    override fun isSupported(): Boolean {
        if (!InbuiltFeatures.git.state.value) return false
        val drawerViewModel = MainActivity.instance?.drawerViewModel ?: return false
        val tab = drawerViewModel.currentDrawerTab ?: return false
        if (tab !is FileTreeTab) return false
        val rootDir = File(tab.root.getAbsolutePath())
        return FileRepositoryBuilder().findGitDir(rootDir).gitDir != null
    }
}

/** Count added/removed lines in a unified diff. */
private fun diffStats(diff: String?): Pair<Int, Int> {
    if (diff == null) return 0 to 0
    var added = 0
    var removed = 0
    diff.split("\n").forEach { l ->
        when {
            l.startsWith("+") && !l.startsWith("+++") -> added++
            l.startsWith("-") && !l.startsWith("---") -> removed++
        }
    }
    return added to removed
}
