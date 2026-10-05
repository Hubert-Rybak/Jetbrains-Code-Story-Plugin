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
        }
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
            ErrorCode.STORY_TOO_LARGE // StoryFolderTest
        assertEquals(ErrorCode.entries.toSet(), covered)
    }
}
