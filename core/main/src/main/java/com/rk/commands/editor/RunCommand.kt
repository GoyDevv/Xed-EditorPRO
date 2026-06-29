package com.rk.commands.editor

import android.view.KeyEvent
import com.rk.DefaultScope
import com.rk.commands.CommandProvider
import com.rk.commands.EditorActionContext
import com.rk.commands.EditorCommand
import com.rk.commands.EditorNonActionContext
import com.rk.commands.KeyCombination
import com.rk.file.FileObject
import com.rk.file.FileWrapper
import com.rk.filetree.FileTreeTab
import com.rk.icons.Icon
import com.rk.resources.drawables
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.runner.ProjectRunner
import com.rk.runner.RunOutputState
import com.rk.settings.Settings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch

/**
 * The editor "Run" (play) button. It is **project-aware** and follows the directory/project you're
 * in: visibility and behaviour are driven by the type of the project currently selected in the
 * drawer (falling back to the open file's project), detected by [ProjectRunner].
 *
 *  - Shown for Python, Node, Rust, Go, static Web, and Fabric/Forge/Gradle projects — independently
 *    of which file (if any) is open, so a `.md`/`.txt` file in a runnable project still shows it.
 *  - Hidden for Android projects and for projects the editor can't identify.
 *
 * Clicking it runs (or builds) the project from its own folder in the sandbox (in the background),
 * or opens the in-app HTML preview for static web projects.
 *
 * **Single build / Stop:** only one build started by this button may run at a time. While such a
 * build is running the button turns into a Stop button (across every editor tab) and clicking it
 * kills the running build instead of starting another. Manually opened terminals are unaffected.
 */
@OptIn(DelicateCoroutinesApi::class)
class RunCommand : EditorCommand() {
    override val id: String = "editor.run"

    /** The project root the user is currently "in" — the drawer's selected project takes priority. */
    private fun drawerProjectRoot(): FileObject? =
        (commandContext.drawerViewModel.currentDrawerTab as? FileTreeTab)?.root

    override fun getLabel(): String =
        if (RunOutputState.isRunning) strings.stop.getString() else strings.run.getString()

    override fun action(editorActionContext: EditorActionContext) {
        // A build is already running → this button acts as Stop and kills it instantly.
        if (RunOutputState.isRunning) {
            RunOutputState.stop()
            return
        }

        val editorTab = editorActionContext.editorTab
        val activity = editorActionContext.currentActivity
        // Prefer the drawer's current project so Run targets the directory the user is in.
        val projectRoot = drawerProjectRoot() ?: editorTab.projectRoot
        CommandProvider.SaveCommand.action(editorActionContext)
        DefaultScope.launch {
            Settings.runs += 1
            ProjectRunner.run(activity = activity, projectRoot = projectRoot, file = editorTab.file)
        }
    }

    override fun isSupported(editorNonActionContext: EditorNonActionContext): Boolean {
        // Keep the (Stop) button available on any editor tab while a build is running.
        if (RunOutputState.isRunning) return true
        // Show whenever the directory/project selected in the drawer is runnable, regardless of the
        // open file's type or project.
        val drawerRoot = drawerProjectRoot()
        if (drawerRoot is FileWrapper && ProjectRunner.canRun(drawerRoot.getAbsolutePath())) return true
        val tab = editorNonActionContext.editorTab
        val rootPath = ProjectRunner.resolveProjectRootPath(tab.projectRoot, tab.file)
        return ProjectRunner.canRun(rootPath)
    }

    override fun getIcon(): Icon =
        if (RunOutputState.isRunning) Icon.ResourceIcon(drawables.stop) else Icon.ResourceIcon(drawables.run)

    override val defaultKeybinds: KeyCombination = KeyCombination(keyCode = KeyEvent.KEYCODE_F5)
}
