package io.github.hubertrybak.codestory.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Gives the validator access to the files under the story root. */
interface StoryFiles {
    /** The folder that story paths are relative to, as shown in messages. */
    val rootLabel: String

    /** Looks up [path]: a normalized path relative to the root, with `/` between folders. */
    fun lookup(path: String): FileLookup
}

sealed interface FileLookup {
    /** [path] is the real path of the file relative to the root, with the case and links resolved. */
    class Found(val path: String, val text: CharSequence) : FileLookup
    data object NotFound : FileLookup
    data object NotAFile : FileLookup
    data object NotText : FileLookup
    data object OutsideRoot : FileLookup
    data object InvalidPath : FileLookup
}

/**
 * Files on disk under [root]. A path that leads out of the root through a symbolic link is
 * [FileLookup.OutsideRoot]; its content is never read. [readText] lets the IDE supply the text
 * that it shows, including unsaved edits.
 */
class RootStoryFiles(
    private val root: Path,
    private val readText: (Path) -> CharSequence = TextFiles::readDisk,
) : StoryFiles {
    override val rootLabel: String = root.toString()

    override fun lookup(path: String): FileLookup {
        val target = try {
            root.resolve(path)
        } catch (_: InvalidPathException) {
            return FileLookup.InvalidPath
        }
        if (!Files.exists(target)) return FileLookup.NotFound
        val (realRoot, realTarget) = try {
            root.toRealPath() to target.toRealPath()
        } catch (_: IOException) {
            return FileLookup.NotFound
        }
        if (!realTarget.startsWith(realRoot)) return FileLookup.OutsideRoot
        if (!Files.isRegularFile(realTarget)) return FileLookup.NotAFile
        val text = try {
            readText(target)
        } catch (_: IOException) {
            return FileLookup.NotText
        }
        if (TextFiles.looksBinary(text)) return FileLookup.NotText
        return FileLookup.Found(realRoot.relativize(realTarget).joinToString("/"), text)
    }
}

object TextFiles {
    private const val BINARY_CHECK_CHARS = 8000

    fun readDisk(path: Path): CharSequence = String(Files.readAllBytes(path), Charsets.UTF_8)

    /** The lines of [text] without their line breaks, numbered like [lineCount]. */
    fun lines(text: CharSequence): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split("\r\n", "\n", "\r")
        return if (lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    /** Counts lines the way an editor numbers them; a final line break does not start a new line. */
    fun lineCount(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        var lines = 1
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
            if ((c == '\n' || c == '\r') && i + 1 < text.length) lines++
            i++
        }
        return lines
    }

    /** Like Git: a NUL character near the start marks a binary file. */
    fun looksBinary(text: CharSequence): Boolean {
        val end = minOf(text.length, BINARY_CHECK_CHARS)
        for (i in 0 until end) {
            if (text[i] == '\u0000') return true
        }
        return false
    }
}
