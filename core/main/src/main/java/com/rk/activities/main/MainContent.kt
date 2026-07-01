package com.rk.activities.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LeadingIconTab
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.compose.dnd.reorder.ReorderContainer
import com.mohamedrejeb.compose.dnd.reorder.ReorderState
import com.mohamedrejeb.compose.dnd.reorder.ReorderableItem
import com.mohamedrejeb.compose.dnd.reorder.rememberReorderState
import com.rk.commands.CommandPalette
import com.rk.commands.CommandProvider
import com.rk.components.compose.utils.addIf
import com.rk.drawer.DrawerViewModel
import com.rk.editor.preloadSelectionColor
import com.rk.filetree.FileAction
import com.rk.filetree.FileActionContext
import com.rk.filetree.FileActionDialogs
import com.rk.filetree.FileActionProvider
import com.rk.filetree.FileIcon
import com.rk.filetree.FileTreeTab
import com.rk.filetree.FileTreeViewModel
import com.rk.filetree.MultiFileAction
import com.rk.filetree.MultiFileActionContext
import com.rk.icons.XedIcon
import com.rk.resources.drawables
import com.rk.runner.RunOutputView
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.tabs.base.Tab
import com.rk.tabs.editor.EditorTab
import com.rk.utils.dialogRes
import com.rk.utils.drawErrorUnderline
import com.rk.utils.getGitColor
import com.rk.utils.getUnderlineColor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MainContent(
    innerPadding: PaddingValues,
    mainViewModel: MainViewModel,
    drawerViewModel: DrawerViewModel,
    fileTreeViewModel: FileTreeViewModel,
    drawerState: DrawerState,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    preloadSelectionColor()

    FileActionDialogs(drawerViewModel, fileTreeViewModel, scope, context)

    // First-launch Auto Setup prompt: offer to set up the sandbox + core tools once. Shown until
    // the user completes it or explicitly skips it.
    var showAutoSetup by rememberSaveable {
        mutableStateOf(!Settings.auto_setup_completed && !Settings.auto_setup_prompt_dismissed)
    }
    if (showAutoSetup) {
        AutoSetupDialog(
            onLaunch = { showAutoSetup = false },
            onDismissForever = {
                Settings.auto_setup_prompt_dismissed = true
                showAutoSetup = false
            },
        )
    }

    if (mainViewModel.isDraggingPalette || mainViewModel.showCommandPalette) {
        val lastUsedCommand = CommandProvider.getForId(Settings.last_used_command)

        CommandPalette(
            progress = if (mainViewModel.showCommandPalette) 1f else mainViewModel.draggingPaletteProgress.value,
            commands = CommandProvider.commandList,
            lastUsedCommand = lastUsedCommand,
            initialChildCommands = mainViewModel.commandPaletteInitialChildCommands,
            initialPlaceholder = mainViewModel.commandPaletteInitialPlaceholder,
            onDismissRequest = { scope.launch { mainViewModel.closeCommandPalette() } },
        )
    }

    Box(Modifier.fillMaxSize().padding(innerPadding)) {
        Column(Modifier.fillMaxSize()) {
        // Open tabs scoped to the selected project/directory (unless "Show all files" is on).
        val visibleTabs = visibleTabsFor(mainViewModel, drawerViewModel)
        val currentProjectPath = (drawerViewModel.currentDrawerTab as? FileTreeTab)?.root?.getAbsolutePath()

        // Keep the active tab within the visible (scoped) set when the project selection changes.
        LaunchedEffect(Settings.show_all_files, currentProjectPath, visibleTabs.size) {
            val cur = mainViewModel.currentTab
            if (cur != null && visibleTabs.isNotEmpty() && cur !in visibleTabs) {
                val idx = mainViewModel.tabs.indexOf(visibleTabs.first())
                if (idx >= 0) mainViewModel.tabManager.setCurrentTab(idx)
            }
        }

        if (visibleTabs.isEmpty()) {
            EmptyEditorState(
                projectPath = currentProjectPath,
                onOpenTree = { scope.launch { drawerState.open() } },
            )
        } else {
            val pagerState = rememberPagerState(pageCount = { visibleTabsFor(mainViewModel, drawerViewModel).size })

            val selectedVisibleIndex = visibleTabs.indexOf(mainViewModel.currentTab).let { if (it < 0) 0 else it }

            LaunchedEffect(selectedVisibleIndex, visibleTabs.size) {
                if (
                    visibleTabs.isNotEmpty() &&
                        selectedVisibleIndex < visibleTabs.size &&
                        pagerState.currentPage != selectedVisibleIndex
                ) {
                    if (Settings.smooth_tabs) {
                        pagerState.animateScrollToPage(selectedVisibleIndex)
                    } else {
                        pagerState.scrollToPage(selectedVisibleIndex)
                    }
                }
            }

            val reorderState = rememberReorderState<Tab>(dragAfterLongPress = true)

            ReorderContainer(state = reorderState) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = selectedVisibleIndex,
                    modifier = Modifier.fillMaxWidth(),
                    edgePadding = 0.dp,
                    divider = {},
                ) {
                    visibleTabs.forEach { tabState ->
                        val index = mainViewModel.tabs.indexOf(tabState)
                        key(tabState) {
                            TabItem(
                                mainViewModel = mainViewModel,
                                fileTreeViewModel = fileTreeViewModel,
                                reorderState = reorderState,
                                tabState = tabState,
                                index = index,
                                showIcon = Settings.show_tab_icons,
                                onCloseThis = {
                                    val tabIndex = mainViewModel.tabs.indexOf(tabState)
                                    if (tabIndex == -1) return@TabItem

                                    if (tabState is EditorTab && tabState.editorState.isDirty) {
                                        dialogRes(
                                            title = strings.file_unsaved.getString(),
                                            msg = strings.ask_unsaved.getString(),
                                            onOk = { mainViewModel.tabManager.removeTab(tabIndex) },
                                            onCancel = {},
                                            okRes = strings.discard,
                                        )
                                    } else {
                                        mainViewModel.tabManager.removeTab(tabIndex)
                                    }
                                },
                                onCloseOthers = { index ->
                                    mainViewModel.tabManager.setCurrentTab(index)

                                    val unsavedOtherTabs =
                                        mainViewModel.tabs.filterIndexed { tabIndex, tab ->
                                            tabIndex != index && (tab as? EditorTab)?.editorState?.isDirty == true
                                        }
                                    if (unsavedOtherTabs.isNotEmpty()) {
                                        dialogRes(
                                            title = strings.files_unsaved.getString(),
                                            msg = strings.ask_multiple_unsaved.getString(),
                                            onOk = { mainViewModel.tabManager.removeOtherTabs() },
                                            onCancel = {},
                                            okRes = strings.discard,
                                        )
                                    } else {
                                        mainViewModel.tabManager.removeOtherTabs()
                                    }
                                },
                                onCloseAll = {
                                    val unsavedTabs =
                                        mainViewModel.tabs.filter { tab ->
                                            (tab as? EditorTab)?.editorState?.isDirty == true
                                        }
                                    if (unsavedTabs.isNotEmpty()) {
                                        dialogRes(
                                            title = strings.files_unsaved.getString(),
                                            msg = strings.ask_multiple_unsaved.getString(),
                                            onOk = { mainViewModel.tabManager.removeAllTabs() },
                                            onCancel = {},
                                            okRes = strings.discard,
                                        )
                                    } else {
                                        mainViewModel.tabManager.removeAllTabs()
                                    }
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().clipToBounds(),
                beyondViewportPageCount = visibleTabs.size,
                userScrollEnabled = false,
                key = { visibleTabs.getOrNull(it).hashCode() },
            ) { page ->
                visibleTabs.getOrNull(page)?.Content()
            }
        }
        }

        // Always-visible floating build/run output — works even with no file open.
        RunOutputView(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * The set of open tabs to display. When "Show all files" is on (or no project/directory is selected
 * in the drawer) every open tab is shown. Otherwise editor tabs are scoped to the currently selected
 * project: only files whose [EditorTab.projectRoot] matches the selected project are shown, so files
 * from different directories don't get mixed together. Non-editor tabs (e.g. previews) always show.
 *
 * Reads snapshot-backed state ([Settings.show_all_files], the drawer selection and the tab list) so
 * callers — including the pager's pageCount lambda — recompose/remeasure when any of them change.
 */
private fun visibleTabsFor(mainViewModel: MainViewModel, drawerViewModel: DrawerViewModel): List<Tab> {
    val all = mainViewModel.tabs
    if (Settings.show_all_files) return all
    val projectPath = (drawerViewModel.currentDrawerTab as? FileTreeTab)?.root?.getAbsolutePath() ?: return all
    return all.filter { tab -> tab !is EditorTab || tab.projectRoot?.getAbsolutePath() == projectPath }
}

@Composable
private fun TabItem(
    mainViewModel: MainViewModel,
    fileTreeViewModel: FileTreeViewModel,
    reorderState: ReorderState<Tab>,
    tabState: Tab,
    index: Int,
    showIcon: Boolean,
    onCloseThis: (Int) -> Unit,
    onCloseOthers: (Int) -> Unit,
    onCloseAll: (Int) -> Unit,
) {
    var calculatedTabWidth by
        remember(
            tabState,
            tabState.tabTitle.value,
            tabState is EditorTab && tabState.editorState.isDirty,
            Settings.show_tab_icons,
        ) {
            mutableStateOf<Int?>(null)
        }

    ReorderableItem(
        state = reorderState,
        key = tabState,
        data = tabState,
        onDragEnter = { state ->
            val index = mainViewModel.tabs.indexOf(tabState)
            val oldIndex = mainViewModel.tabs.indexOf(state.data)

            mainViewModel.tabManager.moveTab(oldIndex, index)
        },
        draggableContent = {
            TabItemContent(
                mainViewModel = mainViewModel,
                fileTreeViewModel = fileTreeViewModel,
                index = index,
                calculatedTabWidth = calculatedTabWidth,
                tabState = tabState,
                onCloseThis = onCloseThis,
                onCloseOthers = onCloseOthers,
                onCloseAll = onCloseAll,
                showIcon = showIcon,
                isDraggableContent = true,
            )
        },
        modifier = Modifier.fillMaxWidth().onSizeChanged { size -> calculatedTabWidth = size.width },
    ) {
        TabItemContent(
            mainViewModel = mainViewModel,
            fileTreeViewModel = fileTreeViewModel,
            index = index,
            calculatedTabWidth = calculatedTabWidth,
            tabState = tabState,
            onCloseThis = onCloseThis,
            onCloseOthers = onCloseOthers,
            onCloseAll = onCloseAll,
            showIcon = showIcon,
        )
    }
}

@Composable
private fun TabItemContent(
    mainViewModel: MainViewModel,
    fileTreeViewModel: FileTreeViewModel,
    index: Int,
    calculatedTabWidth: Int?,
    tabState: Tab,
    onCloseThis: (Int) -> Unit,
    onCloseOthers: (Int) -> Unit,
    onCloseAll: (Int) -> Unit,
    showIcon: Boolean,
    isDraggableContent: Boolean = false,
) {
    var showTabMenu by remember { mutableStateOf(false) }
    var showFileActionMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val density = LocalDensity.current

    val drawerViewModel = (context as MainActivity).drawerViewModel

    val isSelected = mainViewModel.currentTabIndex == index
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant

    val tabModifier =
        Modifier.let { modifier ->
                calculatedTabWidth?.let { width -> modifier.width(with(density) { width.toDp() }) } ?: modifier
            }
            .let { if (isDraggableContent) it.background(backgroundColor.copy(alpha = 0.4f)) else it }

    val onClick: () -> Unit = {
        if (isSelected) {
            showTabMenu = true
        } else {
            mainViewModel.tabManager.setCurrentTab(index)
        }
    }

    val underlineColor = getUnderlineColor(context, fileTreeViewModel, tabState.file)
    val tabText: @Composable () -> Unit = {
        Text(
            text =
                if (tabState is EditorTab && tabState.editorState.isDirty) {
                    "*${tabState.tabTitle.value}"
                } else {
                    tabState.tabTitle.value
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.addIf(underlineColor != null) { drawErrorUnderline(underlineColor!!) },
        )

        DropdownMenu(expanded = showTabMenu, onDismissRequest = { showTabMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(strings.close_this)) },
                onClick = {
                    showTabMenu = false
                    onCloseThis(index)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(strings.close_others)) },
                onClick = {
                    showTabMenu = false
                    onCloseOthers(index)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(strings.close_all)) },
                onClick = {
                    showTabMenu = false
                    onCloseAll(index)
                },
            )
            tabState.file?.let {
                val fileExists by produceState(false) { value = it.exists() }
                DropdownMenuItem(
                    text = { Text(stringResource(strings.file_actions)) },
                    enabled = fileExists,
                    trailingIcon = {
                        Icon(
                            painter = painterResource(drawables.chevron_right),
                            contentDescription = stringResource(strings.open),
                        )
                    },
                    onClick = {
                        showTabMenu = false
                        showFileActionMenu = true
                    },
                )
            }
        }

        tabState.file?.let {
            DropdownMenu(expanded = showFileActionMenu, onDismissRequest = { showFileActionMenu = false }) {
                val root = (tabState as? EditorTab)?.projectRoot
                val actions = remember(it) { FileActionProvider.getActions(it, root) }

                actions.forEach { action ->
                    when (action) {
                        is FileAction -> {
                            DropdownMenuItem(
                                text = { Text(action.title) },
                                leadingIcon = { XedIcon(action.icon, contentDescription = action.title) },
                                enabled = action.isEnabled(it),
                                onClick = {
                                    val context =
                                        FileActionContext(it, root, fileTreeViewModel, drawerViewModel, context)
                                    action.action(context)
                                    showFileActionMenu = false
                                },
                            )
                        }
                        is MultiFileAction -> {
                            val files = listOf(it)
                            DropdownMenuItem(
                                text = { Text(action.title) },
                                leadingIcon = { XedIcon(action.icon, contentDescription = action.title) },
                                enabled = action.isEnabled(files),
                                onClick = {
                                    val context =
                                        MultiFileActionContext(files, root, fileTreeViewModel, drawerViewModel, context)
                                    action.action(context)
                                    showFileActionMenu = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    val gitColor = getGitColor(tabState.file)
    val activeColor = gitColor ?: MaterialTheme.colorScheme.primary
    val inactiveColor = gitColor ?: MaterialTheme.colorScheme.onSurfaceVariant

    if (showIcon && tabState.file != null) {
        LeadingIconTab(
            modifier = tabModifier,
            selected = isSelected,
            onClick = onClick,
            icon = { FileIcon(file = tabState.file!!, iconTint = LocalContentColor.current) },
            text = tabText,
            selectedContentColor = activeColor,
            unselectedContentColor = inactiveColor,
        )
    } else {
        Tab(
            modifier = tabModifier,
            selected = isSelected,
            onClick = onClick,
            text = tabText,
            selectedContentColor = activeColor,
            unselectedContentColor = inactiveColor,
        )
    }
}


/**
 * Shown in the editor area when no file is open. If a project/folder is selected, it summarizes the
 * project (name, path, dominant language) with an "Open file tree" button; otherwise it prompts to
 * open a folder.
 */
@Composable
private fun EmptyEditorState(projectPath: String?, onOpenTree: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(drawables.outline_folder),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(14.dp))

        if (projectPath != null) {
            val root = remember(projectPath) { File(projectPath) }
            Text(
                text = root.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = projectPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            val language by
                produceState<String?>(initialValue = null, projectPath) {
                    value = withContext(Dispatchers.IO) { detectMajorityLanguage(root) }
                }
            language?.let {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(50)) {
                    Text(
                        text = "● $it",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = onOpenTree) {
                Icon(painterResource(drawables.outline_folder), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open file tree")
            }
        } else {
            Text("No folder opened", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Open a folder to start editing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onOpenTree) { Text("Open file tree") }
        }
    }
}

/** Best-effort "majority codebase" detection by counting files per language (bounded walk). */
private fun detectMajorityLanguage(root: File): String? {
    val skip = setOf(".git", "node_modules", "build", ".gradle", ".idea", ".cxx", "out", "dist", ".dart_tool", "vendor")
    val counts = HashMap<String, Int>()
    runCatching {
        root.walkTopDown()
            .onEnter { it.name !in skip }
            .filter { it.isFile }
            .take(6000)
            .forEach { f ->
                val lang = languageForExtension(f.name.substringAfterLast('.', "").lowercase()) ?: return@forEach
                counts[lang] = (counts[lang] ?: 0) + 1
            }
    }
    val top = counts.maxByOrNull { it.value } ?: return null
    val total = counts.values.sum().coerceAtLeast(1)
    return "${top.key} · ${top.value * 100 / total}%"
}

private fun languageForExtension(ext: String): String? =
    when (ext) {
        "kt", "kts" -> "Kotlin"
        "java" -> "Java"
        "py" -> "Python"
        "js", "mjs", "cjs" -> "JavaScript"
        "ts" -> "TypeScript"
        "tsx", "jsx" -> "React"
        "c" -> "C"
        "cpp", "cc", "cxx", "hpp", "hh" -> "C++"
        "h" -> "C/C++"
        "rs" -> "Rust"
        "go" -> "Go"
        "rb" -> "Ruby"
        "php" -> "PHP"
        "swift" -> "Swift"
        "dart" -> "Dart"
        "html", "htm" -> "HTML"
        "css", "scss", "sass" -> "CSS"
        "json" -> "JSON"
        "xml" -> "XML"
        "md", "markdown" -> "Markdown"
        "sh", "bash" -> "Shell"
        "gradle" -> "Gradle"
        "yml", "yaml" -> "YAML"
        "sql" -> "SQL"
        else -> null
    }
