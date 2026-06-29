package com.rk.commands.editor

import com.rk.commands.EditorActionContext
import com.rk.commands.EditorCommand
import com.rk.commands.ToggleableCommand
import com.rk.icons.Icon
import com.rk.resources.drawables
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Settings

/**
 * Toggles the editor's bottom extra-keys bar (Tab / ← → ↑ ↓ / symbols). Off by default; lives in
 * the editor toolbar's overflow (the 3-dot menu) as a toggle so it's easy to turn on/off per taste.
 */
class ToggleExtraKeysCommand : EditorCommand(), ToggleableCommand {
    override val id: String = "editor.toggle_extra_keys"

    override fun getLabel(): String = strings.extra_keys.getString()

    override fun action(editorActionContext: EditorActionContext) {
        Settings.show_extra_keys = !Settings.show_extra_keys
    }

    override fun isOn(): Boolean = Settings.show_extra_keys

    override fun getIcon(): Icon = Icon.ResourceIcon(drawables.keyboard)
}
