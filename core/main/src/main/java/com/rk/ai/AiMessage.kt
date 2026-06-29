package com.rk.ai

/** A tool/function call requested by the model. [arguments] is a raw JSON string. */
data class AiToolCall(val id: String, val name: String, val arguments: String)

/**
 * One message in the conversation, mapped to/from the OpenAI chat format.
 *
 * @param role one of "system" | "user" | "assistant" | "tool".
 * @param content text content (may be empty when the assistant only returns tool calls).
 * @param toolCalls populated on assistant messages that request tools.
 * @param toolCallId set on role="tool" result messages (links back to the call).
 * @param ui a hint for rendering ("text", "tool", "tool_result", "error").
 */
data class AiMessage(
    val role: String,
    val content: String = "",
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolCallId: String? = null,
    val ui: String = "text",
)
