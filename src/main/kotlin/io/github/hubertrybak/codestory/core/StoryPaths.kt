package io.github.hubertrybak.codestory.core

/** Checks the syntax of a story path before any file-system access. */
object StoryPaths {
    sealed interface Result {
        /** [path] is relative, has `/` between folders, and has no empty or `.` segments. */
        class Valid(val path: String) : Result
        data object Absolute : Result
        data object Parent : Result
        data object Invalid : Result
    }

    private val windowsDrive = Regex("^[A-Za-z]:")
    private val uriScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    fun normalize(raw: String): Result {
        val path = raw.trim().replace('\\', '/')
        if (path.startsWith("/") || windowsDrive.containsMatchIn(path) || uriScheme.containsMatchIn(path)) {
            return Result.Absolute
        }
        if (path.any { it.isISOControl() }) return Result.Invalid
        val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (".." in segments) return Result.Parent
        if (segments.isEmpty()) return Result.Invalid
        return Result.Valid(segments.joinToString("/"))
    }
}
