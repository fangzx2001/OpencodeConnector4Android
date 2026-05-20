package com.opencode.remote.ui.chat

import com.opencode.remote.data.api.dto.MessagePart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shared utility for building compact one-line summaries of tool invocations.
 *  Used by both [ChatViewModel] (streaming) and [ChatScreen] (completed messages). */
object ToolSummarizer {

    data class ToolDisplaySummary(
        val label: String,
        val detail: String,
        val toolName: String,
        val status: String?,
        val childSessionId: String? = null,
        val title: String? = null,
    )

    /** Build a summary line from a tool part's structured data.
     *  Uses [MessagePart.tool] (name) and [MessagePart.state.input] (filePath/command) to create compact display. */
    fun summarize(part: MessagePart): String {
        return summarizeDetailed(part).label
    }

    fun summarizeDetailed(part: MessagePart): ToolDisplaySummary {
        val toolName = part.tool ?: return ToolDisplaySummary(
            label = "🔧 tool call",
            detail = part.text.orEmpty(),
            toolName = "tool",
            status = part.state?.status,
            title = part.state?.title,
        )
        val input = part.state?.input

        val filePath = extractJsonString(input, "filePath")
        val command = extractJsonString(input, "command")
        val query = extractJsonString(input, "query")
        val childSessionId = extractJsonString(part.state?.metadata, "sessionId")
            ?: extractJsonString(part.state?.metadata, "sessionID")
        val output = part.state?.output?.takeIf { it.isNotBlank() }
        val inputSummary = buildInputSummary(input)
        val metadataSummary = buildMetadataSummary(part.state?.metadata)
        val baseDetail = buildString {
            part.state?.title?.takeIf { it.isNotBlank() }?.let {
                appendLine(it)
            }
            inputSummary?.let {
                appendLine(it)
            }
            output?.let {
                appendLine(it)
            }
            metadataSummary?.let {
                appendLine(it)
            }
            if (!part.text.isNullOrBlank() && part.text != output) {
                appendLine(part.text)
            }
        }.trim()

        val label = when (toolName) {
            "edit" -> if (filePath != null) "📝 edit ${fileNameFromPath(filePath)}" else "📝 edit"
            "write" -> if (filePath != null) "📝 write ${fileNameFromPath(filePath)}" else "📝 write"
            "read" -> if (filePath != null) "📖 read ${fileNameFromPath(filePath)}" else "📖 read"
            "bash" -> if (command != null) "💻 bash: ${command.take(60)}" else "💻 bash"
            "grep" -> if (query != null) "🔍 grep: ${query.take(40)}" else "🔍 grep"
            "glob" -> if (query != null) "📂 glob: ${query.take(40)}" else "📂 glob"
            "ast_grep_search" -> "🔎 ast-grep"
            "lsp_diagnostics" -> "🩺 diagnostics"
            "look_at" -> "👁 look_at"
            "task" -> "🤖 task"
            "background_output" -> "📤 background"
            "background_cancel" -> "🚫 cancel"
            "todowrite" -> "📋 todo"
            "question" -> "❓ question"
            else -> "🔧 $toolName"
        }

        return ToolDisplaySummary(
            label = label,
            detail = baseDetail.ifBlank { label },
            toolName = toolName,
            status = part.state?.status,
            childSessionId = childSessionId,
            title = part.state?.title,
        )
    }

    /** Extract a one-line summary from raw tool call text for compact display label.
     *  Used as a fallback when tool data arrives as plain text rather than structured JSON. */
    fun summarizeText(text: String): String {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return "tool call"
        val first = lines.first()
        val fileRegex = Regex("""[\w./\\-]+\.\w{1,10}""")
        val fileMatch = fileRegex.find(first)
        return when {
            first.contains("edit", ignoreCase = true) && fileMatch != null -> "edit ${fileMatch.value}"
            first.contains("write", ignoreCase = true) && fileMatch != null -> "write ${fileMatch.value}"
            first.contains("read", ignoreCase = true) && fileMatch != null -> "read ${fileMatch.value}"
            first.contains("bash", ignoreCase = true) -> "bash"
            first.contains("grep", ignoreCase = true) || first.contains("search", ignoreCase = true) -> "search"
            first.contains("glob", ignoreCase = true) -> "list files"
            else -> {
                val summary = lines.firstOrNull { it.isNotBlank() && !it.startsWith("{") && !it.startsWith("\"") }?.take(60)
                    ?: "tool call"
                summary
            }
        }
    }

    /** Extract a string field from a JsonElement input object. */
    private fun extractJsonString(element: kotlinx.serialization.json.JsonElement?, key: String): String? {
        return try {
            (element as? JsonObject)?.get(key)?.let {
                if (it is JsonPrimitive && it.isString) it.content else null
            }
        } catch (_: Exception) { null }
    }

    private fun buildInputSummary(input: JsonElement?): String? {
        val text = when (input) {
            null -> null
            is JsonObject -> input.entries.joinToString("\n") { (key, value) ->
                "$key: ${compactJsonValue(value)}"
            }
            else -> compactJsonValue(input)
        }
        return text?.takeIf { it.isNotBlank() }?.let { "Input\n$it" }
    }

    private fun buildMetadataSummary(metadata: JsonElement?): String? {
        val obj = metadata as? JsonObject ?: return null
        val filtered = obj.entries
            .filterNot { (key, _) -> key.equals("sessionId", ignoreCase = true) || key.equals("sessionID", ignoreCase = true) }
            .joinToString("\n") { (key, value) -> "$key: ${compactJsonValue(value)}" }
        return filtered.takeIf { it.isNotBlank() }?.let { "Metadata\n$it" }
    }

    private fun compactJsonValue(value: JsonElement): String {
        return when (value) {
            is JsonPrimitive -> value.content
            is JsonArray -> value.joinToString(prefix = "[", postfix = "]") { compactJsonValue(it) }
            is JsonObject -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) -> "$key=${compactJsonValue(item)}" }
            else -> value.toString()
        }
    }

    /** Extract just the filename from a full path. */
    private fun fileNameFromPath(path: String): String {
        val normalized = path.replace('\\', '/')
        return normalized.substringAfterLast('/')
    }
}
