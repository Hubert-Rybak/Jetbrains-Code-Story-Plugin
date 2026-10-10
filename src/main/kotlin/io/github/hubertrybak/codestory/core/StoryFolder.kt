package io.github.hubertrybak.codestory.core

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
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

/**
 * A local or shared story file. Story paths are relative to [root]. After the anchor check,
 * [story] has the lines where the code of each step is now, and [statuses] has the status of
 * each step by index.
 */
class StoredStory(
    val id: String,
    val root: Path,
    val file: Path,
    val story: Story,
    val modified: FileTime,
    val statuses: List<StepStatus> = emptyList(),
    val isNew: Boolean = false,
) {
    val date: String get() = checkNotNull(story.createdAt).atOffset(ZoneOffset.UTC).toLocalDate().toString()
    val folder: String get() = file.parent.fileName.toString()
    /** Reader state belongs to a repository and an ID, independently of Local / Shared. */
    val key: String get() = root.resolve(id).toString()

    /** Steps are ok until their anchors are checked. */
    fun status(index: Int): StepStatus = statuses.getOrElse(index) { StepStatus.OK }

    fun resolved(resolution: AnchorResolution) = StoredStory(id, root, file, resolution.story, modified, resolution.statuses, isNew)

    fun withNewMarker(isNew: Boolean) = StoredStory(id, root, file, story, modified, statuses, isNew)
}

/**
 * The `.codestory/` folder at the story [root]: the Git repository root, or the project folder
 * when there is no Git.
 */
class StoryFolder(val root: Path) {
    val localDir: Path = root.resolve(CODESTORY_DIR).resolve(LOCAL_DIR)
    val sharedDir: Path = root.resolve(CODESTORY_DIR).resolve(SHARED_DIR)

    sealed interface SaveResult {
        class Saved(val id: String, val file: Path, val story: Story) : SaveResult
        class TooLarge(val bytes: Int) : SaveResult
        class NotFound(val id: String) : SaveResult
    }

    sealed interface UpdateResult {
        /** [stored] is the story as its file has it now. */
        class Updated(val stored: StoredStory) : UpdateResult
        class Unchanged(val stored: StoredStory) : UpdateResult
        class TooLarge(val bytes: Int) : UpdateResult
        data object NotFound : UpdateResult
    }

    /**
     * Saves a new story as `<yyyy-MM-dd>-<slug>.story.json`, with a number suffix when the name
     * exists. The file is written to a temporary file first and then renamed, so a reader never
     * sees a partial story. With [storyId], replaces that story in its current folder while
     * preserving its ID, creation metadata and comment threads. An unknown ID saves nothing.
     */
    fun save(story: Story, createdAt: Instant, git: StoryGit = StoryGit(), storyId: String? = null): SaveResult = synchronized(saveLock) {
        val existing = if (storyId == null) null else get(storyId) ?: return SaveResult.NotFound(storyId)
        val date = createdAt.atOffset(ZoneOffset.UTC).toLocalDate()
        val baseId = "$date-${slug(story.title)}"
        val id = existing?.id ?: generateSequence(1) { it + 1 }
            .map { if (it == 1) baseId else "$baseId-$it" }
            .first { !exists(it) }
        val assignedIds = story.steps.mapNotNull { it.id }.toMutableSet()
        val revised = story.copy(
            createdAt = existing?.story?.createdAt ?: createdAt,
            git = existing?.story?.git ?: git,
            steps = story.steps.map { step ->
                if (step.id != null) step else {
                    val stepId = generateSequence { UUID.randomUUID().toString() }.first { assignedIds.add(it) }
                    step.copy(id = stepId)
                }
            },
        )
        // Threads belong to the plugin: a revision keeps them on their steps.
        val saved = if (existing == null) revised else StoryRevisions.record(existing.story, StoryThreads.carryOver(existing.story, revised))
        val bytes = StoryFormat.encode(saved, id).toByteArray(Charsets.UTF_8)
        if (bytes.size > StoryLimits.MAX_FILE_BYTES) return SaveResult.TooLarge(bytes.size)

        val file = existing?.file ?: localDir.resolve(id + StoryFormat.FILE_SUFFIX)
        if (hasStorageSymlink(file)) throw IOException("The story folder or file is a symbolic link.")
        Files.createDirectories(file.parent)
        if (file.parent == localDir) ensureGitIgnore()
        writeAtomically(file, bytes)
        refreshPendingIndex()
        SaveResult.Saved(id, file, saved)
    }

    /**
     * Changes the comment threads of a saved story, in its local or shared file. Reads and writes
     * the file under the save lock, so no other change is lost. Readers change shared stories on
     * purpose: the threads travel with them.
     */
    fun update(id: String, change: (Story) -> Story): UpdateResult = synchronized(saveLock) {
        val current = get(id) ?: return UpdateResult.NotFound
        val updated = change(current.story)
        if (updated == current.story) return UpdateResult.Unchanged(current)
        val bytes = StoryFormat.encode(updated, id).toByteArray(Charsets.UTF_8)
        if (bytes.size > StoryLimits.MAX_FILE_BYTES) return UpdateResult.TooLarge(bytes.size)
        writeAtomically(current.file, bytes)
        refreshPendingIndex()
        UpdateResult.Updated(StoredStory(id, root, current.file, updated, Files.getLastModifiedTime(current.file)))
    }

    /**
     * Writes anchors that moved with the code into a local story. Writes only while the file still
     * holds [expected], so that a newer version from an agent is never overwritten, and never
     * changes a shared story. Returns whether the file was written.
     */
    fun updateAnchors(id: String, expected: Story, updated: Story): Boolean = synchronized(saveLock) {
        val current = get(id) ?: return false
        if (current.file.parent != localDir || current.story != expected) return false
        val bytes = StoryFormat.encode(updated, id).toByteArray(Charsets.UTF_8)
        if (bytes.size > StoryLimits.MAX_FILE_BYTES) return false
        writeAtomically(current.file, bytes)
        true
    }

    /** Normalize a validated drop in its original file, without overwriting a newer agent edit. */
    fun acceptDrop(file: Path, expected: String, story: Story): StoredStory? = synchronized(saveLock) {
        require(file.parent == localDir && isValidId(file.fileName.toString().removeSuffix(StoryFormat.FILE_SUFFIX)))
        if (hasStorageSymlink(file) || !Files.isRegularFile(file) || Files.readString(file) != expected) return null
        val id = file.fileName.toString().removeSuffix(StoryFormat.FILE_SUFFIX)
        val text = StoryFormat.encode(story, id)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > StoryLimits.MAX_FILE_BYTES) return null
        ensureGitIgnore()
        if (text != expected) writeAtomically(file, bytes)
        refreshPendingIndex()
        StoredStory(id, root, file, story, Files.getLastModifiedTime(file))
    }

    /** Moves the original bytes, keeping IDs and metadata. Never overwrites another story. */
    fun setShared(stored: StoredStory, shared: Boolean): StoredStory = synchronized(saveLock) {
        val current = selectedFile(stored) ?: throw IOException("The story has moved or was deleted. Refresh the list.")
        val target = (if (shared) sharedDir else localDir).resolve(current.file.fileName)
        if (target == current.file) return current
        if (hasStorageSymlink(target)) throw IOException("The story folder or file is a symbolic link.")
        Files.createDirectories(target.parent)
        if (!shared) ensureGitIgnore()
        // A plain move fails if the destination exists; ATOMIC_MOVE can replace it on some systems.
        Files.move(current.file, target)
        refreshPendingIndex()
        StoredStory(current.id, root, target, current.story, Files.getLastModifiedTime(target))
    }

    /** Deletes only the selected file, so a stale UI selection cannot delete a moved story. */
    fun delete(stored: StoredStory): Boolean = synchronized(saveLock) {
        val current = selectedFile(stored) ?: return false
        Files.delete(current.file)
        refreshPendingIndex()
        true
    }

    /** A small, local-only index for optional agent reminders. It contains no comment or story text. */
    fun refreshPendingIndex() {
        try {
            writePendingIndex()
        } catch (e: IOException) {
            // The story write already succeeded. An optional reminder must not turn it into a failed API call.
            System.getLogger(StoryFolder::class.java.name).log(System.Logger.Level.WARNING, "Cannot update the Code Story feedback index at $root", e)
        }
    }

    private fun writePendingIndex() = synchronized(saveLock) {
        val index = localDir.resolve(PENDING_INDEX)
        if (hasStorageSymlink(index)) throw IOException("The feedback index folder or file is a symbolic link.")
        val pending = list().mapNotNull { stored ->
            val comments = StoryThreads.pending(stored.story)
            if (comments.isEmpty()) null else stored to comments
        }
        // An unused repository needs no local folder just for the index.
        if (pending.isEmpty() && !Files.exists(index)) return@synchronized
        val text = buildJsonObject {
            put("schemaVersion", 1)
            put("pendingComments", pending.sumOf { it.second.size })
            put("stories", buildJsonArray {
                for ((stored, comments) in pending.sortedBy { it.first.id }) add(buildJsonObject {
                    put("storyId", stored.id)
                    put("folder", stored.folder)
                    put("pendingComments", comments.size)
                    put("rounds", buildJsonArray { comments.map { it.round }.distinct().sorted().forEach { add(it) } })
                })
            })
        }.toString() + "\n"
        if (Files.exists(index) && Files.readString(index) == text) return@synchronized
        Files.createDirectories(localDir)
        ensureGitIgnore()
        writeAtomically(index, text.toByteArray(Charsets.UTF_8))
    }

    private fun selectedFile(stored: StoredStory): StoredStory? {
        if (stored.root != root || !isValidId(stored.id) || stored.file !in listOf(localDir, sharedDir).map { it.resolve(stored.id + StoryFormat.FILE_SUFFIX) }) return null
        return read(stored.file) { _, _ -> }
    }

    /** Local and shared stories, newest first. Files that cannot be read are skipped. */
    fun list(onSkipped: (Path, String) -> Unit = { _, _ -> }): List<StoredStory> {
        val files = listOf(localDir, sharedDir).flatMap { dir ->
            if (Files.isSymbolicLink(root.resolve(CODESTORY_DIR)) || Files.isSymbolicLink(dir) || !Files.isDirectory(dir)) emptyList()
            else Files.newDirectoryStream(dir, "*" + StoryFormat.FILE_SUFFIX).use { it.toList() }
        }
        return files.mapNotNull { file -> read(file, onSkipped) }
            .sortedWith(compareByDescending<StoredStory> { it.story.createdAt }.thenByDescending { it.id })
    }

    /** Finds an existing ID without interpreting agent input as a path. */
    fun get(id: String): StoredStory? {
        if (!isValidId(id)) return null
        return listOf(localDir, sharedDir).firstNotNullOfOrNull { dir ->
            read(dir.resolve(id + StoryFormat.FILE_SUFFIX)) { _, _ -> }
        }
    }

    /** [file] relative to the root, with `/` between folders. */
    fun relativePath(file: Path): String = root.relativize(file).joinToString("/")

    private fun read(file: Path, onSkipped: (Path, String) -> Unit): StoredStory? {
        try {
            if (hasStorageSymlink(file)) {
                onSkipped(file, "The story folder or file is a symbolic link.")
                return null
            }
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

    private fun hasStorageSymlink(file: Path): Boolean =
        listOf(root.resolve(CODESTORY_DIR), file.parent, file).any { Files.isSymbolicLink(it) }

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
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
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
        const val PENDING_INDEX = "pending-feedback.json"
        private const val MAX_SLUG_LENGTH = 60
        private val saveLock = Any()
        private val storyIdPattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}-[a-z0-9]+(?:-[a-z0-9]+)*")

        fun isValidId(id: String): Boolean = storyIdPattern.matches(id)

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
