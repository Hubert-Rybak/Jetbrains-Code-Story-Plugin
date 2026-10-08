package io.github.hubertrybak.codestory

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** The exact same skill is shipped in the plugin and in skills/code-story for manual installation. */
object CodeStorySkill {
    val version: String get() = resource("version.txt").trim()
    val files = listOf("SKILL.md", "references/guide.md", "references/story-v1.schema.json", "references/example.story.json", "version.txt")

    data class Installation(val directory: Path, val version: String?, val complete: Boolean) {
        fun needsUpdate(bundledVersion: String) = !complete || version != bundledVersion
    }

    fun directories(base: Path): List<Path> = listOf(".agents", ".claude").map { base.resolve("$it/skills/code-story") }

    /** Both user and project copies are reported, even if the install setting has since changed. */
    fun installed(userHome: Path, projectRoot: Path?): List<Installation> =
        (listOf(userHome) + listOfNotNull(projectRoot)).distinct().flatMap(::directories).mapNotNull { directory ->
            if (!Files.exists(directory)) return@mapNotNull null
            val version = runCatching { Files.readString(directory.resolve("version.txt")).trim().takeIf { it.isNotEmpty() } }.getOrNull()
            Installation(directory, version, files.all { Files.isRegularFile(directory.resolve(it)) })
        }

    /** The version is written last, so a partial installation is never reported as a complete update. */
    fun install(base: Path): List<Path> = directories(base).onEach(::installAt)

    fun update(installations: List<Installation>): List<Path> = installations.map { it.directory }.onEach(::installAt)

    private fun installAt(directory: Path) {
        val contents = files.associateWith(::resource)
        for ((name, text) in contents) {
            val target = directory.resolve(name)
            if (generateSequence(target) { it.parent }.any(Files::isSymbolicLink)) {
                throw IOException("Cannot install the skill through a symbolic link: $target")
            }
            Files.createDirectories(target.parent)
            val temporary = Files.createTempFile(target.parent, ".code-story-", ".tmp")
            try {
                Files.writeString(temporary, text)
                try { Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temporary) }
        }
    }

    private fun resource(name: String): String =
        checkNotNull(CodeStorySkill::class.java.getResourceAsStream("/code-story/skill/$name")) {
            "The bundled Code Story skill is missing $name."
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
