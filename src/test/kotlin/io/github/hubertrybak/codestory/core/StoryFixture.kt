package io.github.hubertrybak.codestory.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** JSON builders for stories in tests. Values are JSON literals, so tests can send wrong types. */
object StoryJson {
    fun story(
        vararg steps: String,
        title: String = "\"Payment flow\"",
        type: String = "\"change\"",
        summary: String = "\"The controller charges the card.\"",
        request: String = "\"Explain the payment flow\"",
        author: String = """{"agent": "Test Agent", "model": "test-model"}""",
        baseRef: String? = null,
    ): String = """{"title": $title, "type": $type, "summary": $summary, "request": $request, "author": $author, """ +
        (if (baseRef == null) "" else """"baseRef": $baseRef, """) + """"steps": [${steps.joinToString(", ")}]}"""

    /** The default anchor is the text of the start line in the fixture files, where line N is `line N`. */
    fun step(
        file: String = "\"src/App.kt\"",
        start: String = "2",
        end: String = "4",
        title: String = "\"Entry point\"",
        comment: String = "\"The app starts here.\"",
        id: String? = null,
        anchor: String? = quoted("line ${start.toIntOrNull() ?: 1}"),
        location: String? = """{"file": $file, "startLine": $start, "endLine": $end${if (anchor == null) "" else ", \"anchorText\": $anchor"}}""",
    ): String = """{${if (id == null) "" else "\"id\": $id, "}"title": $title, "comment": $comment${if (location == null) "" else ", \"location\": $location"}}"""

    fun withoutField(json: String, field: String): String = JsonObject(Json.parseToJsonElement(json).jsonObject - field).toString()

    fun quoted(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/** Files under a story root, and one file outside it. */
class FixtureFiles(val root: Path, val outside: Path) {
    /** `false` when the file system cannot create symbolic links. */
    val symlinks: Boolean

    init {
        write("src/App.kt", (1..10).joinToString("\n", postfix = "\n") { "line $it" })
        write("src/Windows.kt", "one\r\ntwo\r\nthree\r\n")
        write("src/Long.kt", (1..60).joinToString("\n", postfix = "\n") { "line $it" })
        write("src/Twice.kt", "fun a() {\n    return\n}\n\nfun b() {\n    return\n}\n")
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
