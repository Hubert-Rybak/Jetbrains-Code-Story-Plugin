package io.github.hubertrybak.codestory.core

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/** A story file in `.codestory/local/`. Story paths are relative to [root]. */
class StoredStory(val id: String, val root: Path, val file: Path, val story: Story, val modified: FileTime) {
    val date: String get() = checkNotNull(story.createdAt).atOffset(ZoneOffset.UTC).toLocalDate().toString()
}

/**
 * The `.codestory/` folder at the story [root]: the Git repository root, or the project folder
 * when there is no Git.
 */
class StoryFolder(val root: Path) {
    val localDir: Path = root.resolve(CODESTORY_DIR).resolve(LOCAL_DIR)

    sealed interface SaveResult {
        class Saved(val id: String, val file: Path, val story: Story) : SaveResult
        class TooLarge(val bytes: Int) : SaveResult
    }

    /**
     * Saves a new story as `<yyyy-MM-dd>-<slug>.story.json`, with a number suffix when the name
     * exists. The file is written to a temporary file first and then renamed, so a reader never
     * sees a partial story.
     */
    fun save(story: Story, createdAt: Instant, git: StoryGit = StoryGit()): SaveResult = synchronized(saveLock) {
        val date = createdAt.atOffset(ZoneOffset.UTC).toLocalDate()
        val baseId = "$date-${slug(story.title)}"
        val id = generateSequence(1) { it + 1 }
            .map { if (it == 1) baseId else "$baseId-$it" }
            .first { !exists(it) }
        val assignedIds = story.steps.mapNotNull { it.id }.toMutableSet()
        val saved = story.copy(
            createdAt = createdAt,
            git = git,
            steps = story.steps.map { step ->
                if (step.id != null) step else {
                    val stepId = generateSequence { UUID.randomUUID().toString() }.first { assignedIds.add(it) }
                    step.copy(id = stepId)
                }
            },
        )
        val bytes = StoryFormat.encode(saved, id).toByteArray(Charsets.UTF_8)
        if (bytes.size > StoryLimits.MAX_FILE_BYTES) return SaveResult.TooLarge(bytes.size)

        Files.createDirectories(localDir)
        ensureGitIgnore()
        val file = localDir.resolve(id + StoryFormat.FILE_SUFFIX)
        writeAtomically(file, bytes)
        SaveResult.Saved(id, file, saved)
    }

    /** Stories in `.codestory/local/`, newest first. Files that cannot be read are skipped. */
    fun list(onSkipped: (Path, String) -> Unit = { _, _ -> }): List<StoredStory> {
        if (!Files.isDirectory(localDir)) return emptyList()
        val files = Files.newDirectoryStream(localDir, "*" + StoryFormat.FILE_SUFFIX).use { it.toList() }
        return files.mapNotNull { file -> read(file, onSkipped) }
            .sortedWith(compareByDescending<StoredStory> { it.story.createdAt }.thenByDescending { it.id })
    }

    /** [file] relative to the root, with `/` between folders. */
    fun relativePath(file: Path): String = root.relativize(file).joinToString("/")

    private fun read(file: Path, onSkipped: (Path, String) -> Unit): StoredStory? {
        try {
            if (!Files.isRegularFile(file)) return null
            val size = Files.size(file)
            if (size > StoryLimits.MAX_FILE_BYTES) {
                onSkipped(file, "The file is larger than ${StoryLimits.MAX_FILE_BYTES / 1024} KB.")
                return null
            }
            return when (val decoded = StoryFormat.decode(Files.readString(file))) {
                is StoryFormat.Decoded.Valid -> StoredStory(
                    id = file.fileName.toString().removeSuffix(StoryFormat.FILE_SUFFIX),
                    root = root,
                    file = file,
                    story = decoded.story,
                    modified = Files.getLastModifiedTime(file),
                )
                is StoryFormat.Decoded.Invalid -> {
                    onSkipped(file, decoded.reason)
                    null
                }
            }
        } catch (e: IOException) {
            onSkipped(file, e.toString())
            return null
        }
    }

    /** Story IDs stay unique in both story folders, so sharing a story never changes its ID. */
    private fun exists(id: String): Boolean = listOf(LOCAL_DIR, SHARED_DIR).any {
        Files.exists(root.resolve(CODESTORY_DIR).resolve(it).resolve(id + StoryFormat.FILE_SUFFIX))
    }

    private fun ensureGitIgnore() {
        val gitIgnore = localDir.resolve(".gitignore")
        if (!Files.exists(gitIgnore)) {
            writeAtomically(gitIgnore, "# Local Code Story stories stay out of Git.\n*\n".toByteArray(Charsets.UTF_8))
        }
    }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        // Not Files.createTempFile: its owner-only permissions would stay on the story file.
        val temp = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.write(temp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        const val CODESTORY_DIR = ".codestory"
        const val LOCAL_DIR = "local"
        const val SHARED_DIR = "shared"
        private const val MAX_SLUG_LENGTH = 60
        private val saveLock = Any()

        /** The story folder for a project: at the nearest Git repository root, or in the project folder. */
        fun forProject(projectDir: Path): StoryFolder = StoryFolder(findRoot(projectDir))

        fun findRoot(projectDir: Path): Path {
            val start = projectDir.toAbsolutePath().normalize()
            return generateSequence(start) { it.parent }.firstOrNull { Files.exists(it.resolve(".git")) } ?: start
        }

        /** A file-name part from the title: lowercase ASCII letters and digits, joined by `-`. */
        fun slug(title: String): String {
            val ascii = Normalizer.normalize(title, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
            val slug = ascii.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val short = if (slug.length <= MAX_SLUG_LENGTH) slug else slug.take(MAX_SLUG_LENGTH + 1).substringBeforeLast('-')
            return short.take(MAX_SLUG_LENGTH).trim('-').ifEmpty { "story" }
        }
    }
}
