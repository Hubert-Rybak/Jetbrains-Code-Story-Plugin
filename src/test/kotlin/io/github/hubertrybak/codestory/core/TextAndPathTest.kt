package io.github.hubertrybak.codestory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAndPathTest {
    @Test
    fun `counts lines like an editor`() {
        val cases = mapOf(
            "" to 0,
            "a" to 1,
            "a\n" to 1,
            "a\nb" to 2,
            "a\nb\n" to 2,
            "a\r\nb\r\n" to 2,
            "a\rb" to 2,
            "\n" to 1,
            "\n\n" to 2,
            "a\n\nb" to 3,
        )
        for ((text, lines) in cases) {
            assertEquals("lines of ${text.replace("\n", "\\n").replace("\r", "\\r")}", lines, TextFiles.lineCount(text))
            assertEquals("split lines of ${text.replace("\n", "\\n").replace("\r", "\\r")}", lines, TextFiles.lines(text).size)
        }
        assertEquals(listOf("a", "", "b"), TextFiles.lines("a\r\n\rb\n"))
        assertEquals(listOf("  indented", "tab\tinside "), TextFiles.lines("  indented\ntab\tinside \n"))
    }

    @Test
    fun `finds binary content`() {
        assertTrue(TextFiles.looksBinary("PNG\u0000\u0000"))
        assertFalse(TextFiles.looksBinary("fun main() {}\n"))
        assertFalse(TextFiles.looksBinary("x".repeat(9000) + "\u0000"))
    }

    @Test
    fun `normalizes relative paths`() {
        val cases = mapOf(
            "src/App.kt" to "src/App.kt",
            "./src/App.kt" to "src/App.kt",
            "src\\main\\App.kt" to "src/main/App.kt",
            "src//App.kt" to "src/App.kt",
            "  src/App.kt " to "src/App.kt",
            "src/./App.kt" to "src/App.kt",
            "docs/a:b.txt" to "docs/a:b.txt",
        )
        for ((raw, normalized) in cases) {
            assertEquals(raw, normalized, (StoryPaths.normalize(raw) as StoryPaths.Result.Valid).path)
        }
    }

    @Test
    fun `rejects paths that can leave the project`() {
        val cases = mapOf(
            "/etc/passwd" to StoryPaths.Result.Absolute,
            "\\Windows\\win.ini" to StoryPaths.Result.Absolute,
            "C:\\repo\\App.kt" to StoryPaths.Result.Absolute,
            "c:repo/App.kt" to StoryPaths.Result.Absolute,
            "\\\\server\\share\\App.kt" to StoryPaths.Result.Absolute,
            "https://example.com/App.kt" to StoryPaths.Result.Absolute,
            "../App.kt" to StoryPaths.Result.Parent,
            "src/../../App.kt" to StoryPaths.Result.Parent,
            "src\\..\\App.kt" to StoryPaths.Result.Parent,
            "" to StoryPaths.Result.Invalid,
            "." to StoryPaths.Result.Invalid,
            "src/\nApp.kt" to StoryPaths.Result.Invalid,
        )
        for ((raw, result) in cases) {
            assertEquals(raw, result, StoryPaths.normalize(raw))
        }
    }

    @Test
    fun `every error code has a test`() {
        val covered = ValidationCases.all.flatMap { case -> case.expected.map { it.second } }.toSet() +
            setOf(ErrorCode.STORY_TOO_LARGE, ErrorCode.STORY_NOT_FOUND, ErrorCode.INVALID_STORY_ID, ErrorCode.AMBIGUOUS_STORY_ID, ErrorCode.STEP_NOT_FOUND,
                ErrorCode.INVALID_REPOSITORY, ErrorCode.REPOSITORY_REQUIRED, ErrorCode.CROSS_REPOSITORY) + // Folder, agent API, patch and management tests
            setOf(ErrorCode.DUPLICATE_THREAD_ID, ErrorCode.THREAD_NOT_FOUND, ErrorCode.DUPLICATE_REPLY, ErrorCode.ALREADY_ANSWERED,
                ErrorCode.NOT_FETCHED, ErrorCode.OUTCOME_MISMATCH, ErrorCode.STALE_STEPS) // Thread format, thread state machine and feedback tool tests
        assertEquals(ErrorCode.entries.toSet(), covered)
    }

    @Test
    fun `every writing warning has a table-driven test`() {
        val covered = WritingCases.all.flatMap { it.expected.toList() }.toSet() +
            setOf(WarningCode.UNKNOWN_BASE_REF) // Change information tests
        assertEquals(WarningCode.entries.toSet(), covered)
    }
}
