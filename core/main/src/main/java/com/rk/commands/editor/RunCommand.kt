package com.rk.commands.editor

import android.view.KeyEvent
import com.rk.DefaultScope
import com.rk.commands.CommandProvider
import com.rk.commands.EditorActionContext
import com.rk.commands.EditorCommand
import com.rk.commands.EditorNonActionContext
import com.rk.commands.KeyCombination
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
 * The editor "Run" (play) button. It is **project-aware**: visibility and behaviour are driven by
 * the type of the project the open file belongs to (see [ProjectRunner]).
 *
 *  - Shown for Python, Node, Rust, Go, static Web, Android, and Fabric/Forge/Gradle projects.
 *  - Hidden only for projects the editor can't identify.
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
        CommandProvider.SaveCommand.action(editorActionContext)
        DefaultScope.launch {
            Settings.runs += 1
            ProjectRunner.run(activity = activity, projectRoot = editorTab.projectRoot, file = editorTab.file)
        }
    }

    override fun isSupported(editorNonActionContext: EditorNonActionContext): Boolean {
        // Keep the (Stop) button available on any editor tab while a build is running so it can be
        // stopped from anywhere.
        if (RunOutputState.isRunning) return true
        val tab = editorNonActionContext.editorTab
        val rootPath = ProjectRunner.resolveProjectRootPath(tab.projectRoot, tab.file)
        return ProjectRunner.canRun(rootPath)
    }

    override fun getIcon(): Icon =
        if (RunOutputState.isRunning) Icon.ResourceIcon(drawables.stop) else Icon.ResourceIcon(drawables.run)

    override val defaultKeybinds: KeyCombination = KeyCombination(keyCode = KeyEvent.KEYCODE_F5)
}
