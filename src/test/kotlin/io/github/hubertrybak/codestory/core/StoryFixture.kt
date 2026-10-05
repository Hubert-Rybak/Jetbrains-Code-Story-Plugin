package io.github.hubertrybak.codestory.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** JSON builders for stories in tests. Values are JSON literals, so tests can send wrong types. */
object StoryJson {
    fun story(vararg steps: String, title: String = "\"Payment flow\""): String =
        """{"title": $title, "steps": [${steps.joinToString(", ")}]}"""

    fun step(
        file: String = "\"src/App.kt\"",
        start: String = "2",
        end: String = "4",
        title: String = "\"Entry point\"",
        comment: String = "\"The app starts here.\"",
    ): String = """{"title": $title, "comment": $comment, "location": {"file": $file, "startLine": $start, "endLine": $end}}"""

    fun quoted(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/** Files under a story root, and one file outside it. */
class FixtureFiles(val root: Path, val outside: Path) {
    /** `false` when the file system cannot create symbolic links. */
    val symlinks: Boolean

    init {
        write("src/App.kt", (1..10).joinToString("\n", postfix = "\n") { "line $it" })
        write("src/Windows.kt", "one\r\ntwo\r\nthree\r\n")
        write("src/empty.txt", "")
        Files.createDirectories(root.resolve("docs"))
        Files.write(root.resolve("src/logo.png"), byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 0x0D))
        Files.writeString(Files.createDirectories(outside).resolve("secret.kt"), "secret\n")
        symlinks = try {
            Files.createSymbolicLink(root.resolve("inside-link.kt"), root.resolve("src/App.kt"))
            Files.createSymbolicLink(root.resolve("outside-link.kt"), outside.resolve("secret.kt"))
            Files.createSymbolicLink(root.resolve("outside-dir"), outside)
            true
        } catch (_: IOException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    fun write(path: String, text: String): Path {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return file
    }
}
