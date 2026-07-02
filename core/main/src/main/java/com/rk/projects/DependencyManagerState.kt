package com.rk.projects

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Process-wide visibility for the full-screen Dependency Manager. Set by the toolbar (works with just
 * a directory selected — no open file needed) and by the editor command; rendered by MainContent.
 */
object DependencyManagerState {
    var visible by mutableStateOf(false)
        private set

    var projectRoot by mutableStateOf<File?>(null)
        private set

    fun open(root: File) {
        projectRoot = root
        visible = true
    }

    fun close() {
        visible = false
    }
}
