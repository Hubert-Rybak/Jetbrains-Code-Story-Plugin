package io.github.hubertrybak.codestory.core

import java.net.URI

/** Only the story's text subset can produce HTML. No input HTML or remote resources are loaded. */
object StoryMarkdown {
    private val inline = Regex("`([^`\\n]+)`|\\*\\*([^\\n]+?)\\*\\*|(?<!!)\\[([^]\\n]+)]\\(([^)\\n]+)\\)")
    private val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val fileLines = Regex("#L([1-9][0-9]*)(?:-L([1-9][0-9]*))?$")

    data class FileLink(val path: String, val startLine: Int, val endLine: Int)

    fun render(text: String): String = buildString {
        var inList = false
        for (line in text.lines()) {
            val bullet = Regex("^[-*+]\\s+(.+)$").matchEntire(line.trim())
            if (bullet != null) {
                if (!inList) append("<ul>")
                inList = true
                append("<li>${renderInline(bullet.groupValues[1])}</li>")
            } else {
                if (inList) append("</ul>")
                inList = false
                if (line.isNotBlank()) append("<p>${renderInline(line)}</p>")
            }
        }
        if (inList) append("</ul>")
    }

    fun isWebLink(target: String): Boolean = runCatching {
        val uri = URI(target)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    fun fileLink(target: String): FileLink? {
        if (scheme.containsMatchIn(target) || target.startsWith("//") || target.any { it.isISOControl() }) return null
        val lines = fileLines.find(target)
        if ('#' in target && lines == null) return null
        val path = (StoryPaths.normalize(target.substringBefore('#')) as? StoryPaths.Result.Valid)?.path ?: return null
        val start = if (lines == null) 1 else lines.groupValues[1].toIntOrNull() ?: return null
        val end = if (lines == null || lines.groupValues[2].isEmpty()) start else lines.groupValues[2].toIntOrNull() ?: return null
        return FileLink(path, start, end).takeIf { end >= start }
    }

    fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun renderInline(text: String): String = buildString {
        var offset = 0
        for (match in inline.findAll(text)) {
            append(escape(text.substring(offset, match.range.first)))
            val code = match.groups[1]?.value
            val bold = match.groups[2]?.value
            val label = match.groups[3]?.value
            val target = match.groups[4]?.value
            append(when {
                code != null -> "<code>${escape(code)}</code>"
                bold != null -> "<b>${renderInline(bold)}</b>"
                target != null && (isWebLink(target) || fileLink(target) != null) -> "<a href=\"${escape(target)}\">${escape(label!!)}</a>"
                else -> escape(match.value)
            })
            offset = match.range.last + 1
        }
        append(escape(text.substring(offset)))
    }
}
