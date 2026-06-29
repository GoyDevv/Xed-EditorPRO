package com.rk.commands.global

import android.content.Intent
import android.view.KeyEvent
import com.rk.activities.main.MainActivity
import com.rk.activities.terminal.Terminal
import com.rk.commands.ActionContext
import com.rk.commands.GlobalCommand
import com.rk.commands.KeyCombination
import com.rk.icons.Icon
import com.rk.resources.drawables
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.runner.ProjectRunner
import com.rk.settings.app.InbuiltFeatures
import com.rk.tabs.editor.EditorTab

class TerminalCommand : GlobalCommand() {
    override val id: String = "global.terminal"

    override fun getLabel(): String = strings.terminal.getString()

    override fun action(actionContext: ActionContext) {
        val activity = actionContext.currentActivity
        val intent = Intent(activity, Terminal::class.java)
        // When opening a brand-new terminal, start it in the current project/file directory using
        // the same resolution the Run button uses, instead of always defaulting to the sandbox
        // home. If a terminal session is already open it is reused as before and this is ignored.
        currentProjectDir()?.let { intent.putExtra("cwd", it) }
        activity.startActivity(intent)
    }

    /** The current editor tab's project directory, translated to a sandbox-reachable path. */
    private fun currentProjectDir(): String? {
        val tab = MainActivity.instance?.viewModel?.tabManager?.currentTab as? EditorTab ?: return null
        val rootPath = ProjectRunner.resolveProjectRootPath(tab.projectRoot, tab.file) ?: return null
        return ProjectRunner.toSandboxPath(rootPath)
    }

    override fun isSupported(): Boolean = InbuiltFeatures.terminal.state.value

    override fun getIcon(): Icon = Icon.ResourceIcon(drawables.terminal)

    override val defaultKeybinds: KeyCombination = KeyCombination(keyCode = KeyEvent.KEYCODE_J, ctrl = true)
}
