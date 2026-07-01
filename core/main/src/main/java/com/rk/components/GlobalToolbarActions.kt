package com.rk.components

import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.rk.DefaultScope
import com.rk.activities.main.MainActivity
import com.rk.activities.main.MainViewModel
import com.rk.activities.main.drawerStateRef
import com.rk.activities.main.fileTreeViewModel
import com.rk.activities.main.searchViewModel
import com.rk.commands.ActionContext
import com.rk.commands.ToolbarConfiguration
import com.rk.drawer.DrawerViewModel
import com.rk.file.FileObject
import com.rk.file.FileWrapper
import com.rk.icons.Icon
import com.rk.projects.DetectedProjectType
import com.rk.runner.ProjectRunner
import com.rk.runner.RunOutputState
import com.rk.file.child
import com.rk.file.createFileIfNot
import com.rk.file.toFileObject
import com.rk.filetree.FileTreeTab
import com.rk.icons.CreateNewFile
import com.rk.icons.XedIcon
import com.rk.icons.XedIcons
import com.rk.resources.drawables
import com.rk.resources.strings
import com.rk.search.CodeSearchDialog
import com.rk.search.FileSearchDialog
import com.rk.utils.application
import com.rk.utils.errorDialog
import com.rk.utils.getTempDir
import com.rk.utils.toast
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

var addDialog by mutableStateOf(false)
var fileSearchDialog by mutableStateOf(false)
var codeSearchDialog by mutableStateOf(false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalToolbarActions(viewModel: MainViewModel, drawerViewModel: DrawerViewModel) {
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var tempFileNameDialog by remember { mutableStateOf(false) }
    var rootFileDialog by remember { mutableStateOf(false) }
    var rootFilePath by remember { mutableStateOf("") }

    val commands by remember { derivedStateOf { ToolbarConfiguration.globalCommands } }

    if (viewModel.tabs.isEmpty() || viewModel.currentTab?.showGlobalActions == true) {
        // Run / Stop (and Android Sync) driven by the project selected in the drawer, so they work
        // even with no file open — the run button detects the chosen directory/project.
        val projectRoot: FileObject? = (drawerViewModel.currentDrawerTab as? FileTreeTab)?.root
        if (projectRoot is FileWrapper) {
            val rootPathStr = projectRoot.getAbsolutePath()
            if (RunOutputState.isRunning || ProjectRunner.canRun(rootPathStr)) {
                IconButton(
                    onClick = {
                        if (RunOutputState.isRunning) {
                            RunOutputState.stop()
                        } else {
                            activity?.let { act -> DefaultScope.launch { ProjectRunner.run(act, projectRoot, projectRoot) } }
                        }
                    }
                ) {
                    XedIcon(
                        if (RunOutputState.isRunning) Icon.ResourceIcon(drawables.stop)
                        else Icon.ResourceIcon(drawables.run)
                    )
                }
            }
            if (!RunOutputState.isRunning && ProjectRunner.detect(rootPathStr) == DetectedProjectType.ANDROID) {
                IconButton(
                    onClick = {
                        activity?.let { act -> DefaultScope.launch { ProjectRunner.sync(act, projectRoot, projectRoot) } }
                    }
                ) {
                    XedIcon(Icon.ResourceIcon(drawables.refresh))
                }
            }
        }

        for (command in commands) {
            if (command.isSupported()) {
                IconButton(
                    enabled = command.isEnabled(),
                    onClick = {
                        activity?.let {
                            command.performCommand(ActionContext(it))
                        }
                    },
                ) {
                    XedIcon(command.getIcon())
                }
            }
        }
    }

    if (fileSearchDialog && drawerViewModel.currentDrawerTab is FileTreeTab) {
        FileSearchDialog(
            mainViewModel = viewModel,
            searchViewModel = searchViewModel.get()!!,
            projectFile = (drawerViewModel.currentDrawerTab as FileTreeTab).root,
            onFinish = { fileSearchDialog = false },
            onSelect = { projectFile, fileObject ->
                scope.launch {
                    if (fileObject.isFile()) {
                        viewModel.editorManager.openFile(
                            fileObject = fileObject,
                            projectRoot = projectFile,
                            checkDuplicate = true,
                            switchToTab = true,
                        )
                        drawerStateRef.get()?.close()
                    } else {
                        fileTreeViewModel.get()?.goToFolder(projectFile, fileObject)
                        drawerStateRef.get()!!.open()
                    }
                }
            },
        )
    }

    if (codeSearchDialog && drawerViewModel.currentDrawerTab is FileTreeTab) {
        CodeSearchDialog(
            mainViewModel = viewModel,
            searchViewModel = searchViewModel.get()!!,
            projectFile = (drawerViewModel.currentDrawerTab as FileTreeTab).root,
            onFinish = { codeSearchDialog = false },
        )
    }

    if (addDialog) {
        ModalBottomSheet(onDismissRequest = { addDialog = false }) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 0.dp)) {
                AddDialogItem(resId = drawables.file, title = stringResource(strings.temp_file)) {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    intent.setType("application/octet-stream")
                    intent.putExtra(Intent.EXTRA_TITLE, "newfile.txt")

                    val activities =
                        application!!.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)

                    if (activities.isEmpty()) {
                        errorDialog(strings.unsupported_feature)
                    } else {
                        tempFileNameDialog = true
                    }

                    addDialog = false
                }

                if ((drawerViewModel.currentDrawerTab as? FileTreeTab)?.root != null) {
                    AddDialogItem(icon = XedIcons.CreateNewFile, title = "New file in project root") {
                        addDialog = false
                        rootFilePath = ""
                        rootFileDialog = true
                    }
                }

                AddDialogItem(icon = XedIcons.CreateNewFile, title = stringResource(strings.new_file)) {
                    addDialog = false
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                    intent.setType("application/octet-stream")
                    intent.putExtra(Intent.EXTRA_TITLE, "newfile.txt")

                    val activities =
                        application!!.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                    if (activities.isEmpty()) {
                        errorDialog(strings.unsupported_feature)
                    } else {
                        MainActivity.instance?.apply {
                            fileManager.createNewFile(mimeType = "*/*", title = "newfile.txt") {
                                if (it != null) {
                                    lifecycleScope.launch {
                                        viewModel.editorManager.openFile(
                                            it,
                                            projectRoot = null,
                                            checkDuplicate = true,
                                            switchToTab = true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                AddDialogItem(resId = drawables.file_symlink, title = stringResource(strings.open_file)) {
                    addDialog = false
                    MainActivity.instance?.apply {
                        fileManager.requestOpenFile(mimeType = "*/*") {
                            if (it != null) {
                                lifecycleScope.launch {
                                    viewModel.editorManager.openFile(
                                        it.toFileObject(expectedIsFile = true),
                                        checkDuplicate = true,
                                        projectRoot = null,
                                        switchToTab = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (tempFileNameDialog) {
        var fileName by remember { mutableStateOf("untitled.txt") }
        SingleInputDialog(
            title = stringResource(strings.temp_file),
            inputValue = fileName,
            onInputValueChange = { fileName = it },
            onConfirm = {
                val requested = fileName.ifBlank { "untitled.txt" }
                tempFileNameDialog = false
                DefaultScope.launch(Dispatchers.IO) {
                    // do not change getTempDir().child("temp_editor") — it's used for checks in EditorTab
                    val tempDir = getTempDir().child("temp_editor")
                    val tempFile = FileWrapper(tempDir.child(uniqueTempName(tempDir, requested)))
                    tempFile.createFileIfNot()
                    viewModel.editorManager.openFile(tempFile, projectRoot = null, switchToTab = true)
                }
            },
            onDismiss = { tempFileNameDialog = false },
            singleLineMode = true,
            confirmText = stringResource(strings.ok),
            inputLabel = stringResource(strings.file_name),
        )
    }

    if (rootFileDialog) {
        val projectRootObj = (drawerViewModel.currentDrawerTab as? FileTreeTab)?.root
        SingleInputDialog(
            title = "New file in project root",
            inputValue = rootFilePath,
            onInputValueChange = { rootFilePath = it },
            onConfirm = {
                val rel = rootFilePath.trim().trimStart('/')
                rootFileDialog = false
                if (projectRootObj != null && rel.isNotBlank()) {
                    DefaultScope.launch(Dispatchers.IO) {
                        runCatching {
                            val base = File(projectRootObj.getAbsolutePath())
                            val target = File(base, rel)
                            target.parentFile?.mkdirs()
                            if (!target.exists()) target.createNewFile()
                            viewModel.editorManager.openFile(
                                FileWrapper(target),
                                projectRoot = projectRootObj,
                                switchToTab = true,
                            )
                        }.onFailure { toast(it.message ?: "Could not create file") }
                    }
                }
                rootFilePath = ""
            },
            onDismiss = {
                rootFileDialog = false
                rootFilePath = ""
            },
            singleLineMode = true,
            confirmText = stringResource(strings.ok),
            inputLabel = "Path, e.g. src/utils/File.kt — any /folders/ in the path are created for you",
        )
    }
}

/** Next free name in [dir] for [base] (untitled.txt -> untitled1.txt -> …). No state side effects. */
private fun uniqueTempName(dir: File, base: String): String {
    if (!dir.child(base).exists()) return base
    val ext = base.substringAfterLast('.', "")
    val name = base.substringBeforeLast('.', base)
    var i = 1
    var candidate: String
    do {
        candidate = if (ext.isNotEmpty()) "$name$i.$ext" else "$name$i"
        i++
    } while (dir.child(candidate).exists())
    return candidate
}
