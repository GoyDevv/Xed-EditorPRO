package com.rk.ai

import androidx.compose.runtime.mutableStateListOf

/**
 * The agent's task list (a todo plan it builds and ticks off as it works), mirrored into Compose
 * state so the chat UI can show live progress. Process-wide (one active plan at a time), written by
 * the set_tasks / complete_task tools in [AiTools] and read by [AiTab].
 */
object AiTaskStore {
    data class AiTask(val text: String, val done: Boolean = false)

    val tasks = mutableStateListOf<AiTask>()

    fun set(items: List<String>) {
        tasks.clear()
        items.filter { it.isNotBlank() }.forEach { tasks.add(AiTask(it.trim())) }
    }

    fun add(text: String) {
        if (text.isNotBlank()) tasks.add(AiTask(text.trim()))
    }

    /** Mark a task done by 1-based index. */
    fun complete(oneBasedIndex: Int) {
        val i = oneBasedIndex - 1
        if (i in tasks.indices) tasks[i] = tasks[i].copy(done = true)
    }

    fun clear() = tasks.clear()

    /** A compact textual view returned to the model. */
    fun render(): String =
        if (tasks.isEmpty()) "(no tasks)"
        else tasks.mapIndexed { i, t -> "${i + 1}. [${if (t.done) "x" else " "}] ${t.text}" }.joinToString("\n")
}
