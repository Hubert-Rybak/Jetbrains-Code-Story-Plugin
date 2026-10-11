package io.github.hubertrybak.codestory

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.hubertrybak.codestory.core.ErrorCode
import io.github.hubertrybak.codestory.core.ReportItem
import io.github.hubertrybak.codestory.core.Story
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryFolder
import io.github.hubertrybak.codestory.core.StoryFormat
import io.github.hubertrybak.codestory.core.StoryLimits
import io.github.hubertrybak.codestory.core.StoryThreads
import io.github.hubertrybak.codestory.core.ValidationResult
import io.github.hubertrybak.codestory.ui.StoryPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Watches only .codestory and its two story folders; no project-wide scan or MCP dependency. */
@Service(Service.Level.PROJECT)
class StoryFileWatcher(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val started = AtomicBoolean()
    @Volatile private var watcher: WatchService? = null
    @Volatile private var job: Job? = null
    @Volatile var problems: Map<Path, List<ReportItem>> = emptyMap()
        private set
    private val seen = HashMap<Path, String>()
    private val accepted = HashMap<Path, Story>()
    private val scanLock = Mutex()
    private val acceptance get() = project.service<StoryAcceptanceState>()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        job = scope.launch(Dispatchers.IO) {
            val service = CodeStoryService.getInstance(project)
            val projectRoot = Path.of(checkNotNull(project.basePath))
            try {
                projectRoot.fileSystem.newWatchService().use { watch ->
                    watcher = watch
                    val registrations = HashMap<Path, Registration>()
                    var rescan = true
                    while (isActive) {
                        var retry = false
                        val folders = service.storyFolders()
                        scanLock.withLock {
                            val directories = folders.flatMap { listOf(it.root.resolve(StoryFolder.CODESTORY_DIR), it.localDir, it.sharedDir) }.toSet()
                            for (path in registrations.keys.toList().filter { it !in directories }) {
                                registrations.remove(path)?.key?.cancel()
                            }
                            for (path in (seen.keys + accepted.keys + problems.keys).filter { it !in directories && it.parent !in directories }) {
                                seen.remove(path)
                                accepted.remove(path)
                                setProblems(path, emptyList())
                            }
                            for (folder in folders) {
                                try {
                                    val registered = registerFolders(folder, watch, registrations)
                                    if (registered || rescan) scan(folder)
                                    setProblems(folder.root.resolve(StoryFolder.CODESTORY_DIR), emptyList())
                                } catch (e: IOException) {
                                    setProblems(folder.root.resolve(StoryFolder.CODESTORY_DIR), listOf(problem("Cannot watch story files: ${e.message}")))
                                    retry = true
                                }
                            }
                        }
                        val key = runInterruptible { watch.poll(1, TimeUnit.SECONDS) }
                        key?.pollEvents()
                        key?.reset()
                        rescan = key != null || retry
                    }
                }
            } catch (_: ClosedWatchServiceException) {
                // Project closed or plugin unloaded.
            } catch (e: IOException) {
                setProblems(projectRoot.resolve(StoryFolder.CODESTORY_DIR), listOf(problem("Cannot watch story files: ${e.message}")))
            }
        }
    }

    /** Sharing is a file move, not a delivery. Serialize it with scans so Unshare never auto-plays a drop. */
    fun setShared(stored: StoredStory, shared: Boolean): StoredStory = runBlocking {
        scanLock.withLock {
            val moved = StoryFolder(stored.root).setShared(stored, shared)
            seen.remove(stored.file)
            accepted.remove(stored.file)
            seen[moved.file] = hash(Files.readString(moved.file))
            accepted[moved.file] = moved.story
            acceptance.forget(stored.file)
            acceptance.remember(moved)
            setProblems(stored.file, emptyList())
            setProblems(moved.file, emptyList())
            moved
        }
    }

    private data class Registration(val key: WatchKey, val fileKey: Any?)

    private fun registerFolders(folder: StoryFolder, watch: WatchService, keys: MutableMap<Path, Registration>): Boolean {
        val directory = folder.root.resolve(StoryFolder.CODESTORY_DIR)
        if (Files.isSymbolicLink(directory)) throw IOException(".codestory must not be a symbolic link.")
        Files.createDirectories(directory)
        var added = false
        for (path in listOf(directory, folder.localDir, folder.sharedDir)) {
            if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
                keys.remove(path)?.key?.cancel()
                continue
            }
            val fileKey = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
            val existing = keys[path]
            // A moved directory must not leave a watch running outside .codestory.
            if (existing?.key?.isValid != true || existing.fileKey != fileKey) {
                existing?.key?.cancel()
                keys[path] = Registration(path.register(watch, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE), fileKey)
                added = true
            }
        }
        return added
    }

    private suspend fun scan(folder: StoryFolder) {
        val present = mutableSetOf<Path>()
        for (directory in listOf(folder.localDir, folder.sharedDir)) {
            if (Files.isSymbolicLink(directory)) {
                setProblems(directory, listOf(problem("The story folder is a symbolic link.")))
                continue
            }
            setProblems(directory, emptyList())
            if (!Files.isDirectory(directory)) continue
            val files = Files.newDirectoryStream(directory, "*${StoryFormat.FILE_SUFFIX}").use { it.toList().sorted() }
            for (file in files) {
                present.add(file)
                try {
                    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, NOFOLLOW_LINKS)) {
                        seen.remove(file)
                        setProblems(file, listOf(problem("The story must be a regular file, not a symbolic link.")))
                        continue
                    }
                    if (Files.size(file) > StoryLimits.MAX_FILE_BYTES) {
                        seen.remove(file)
                        setProblems(file, listOf(ReportItem.storyTooLarge(StoryLimits.MAX_FILE_BYTES + 1)))
                        continue
                    }
                    val text = Files.readString(file)
                    val hash = hash(text)
                    if (seen.put(file, hash) == hash) continue
                    if (directory == folder.localDir) receive(folder, file, text)
                    else when (val result = StoryFormat.decode(text)) {
                        is StoryFormat.Decoded.Invalid -> setProblems(file, listOf(problem(result.reason)))
                        is StoryFormat.Decoded.Valid -> {
                            accepted.put(file, result.story)?.let { threadsChanged(folder, file, it, result.story) }
                            setProblems(file, emptyList())
                        }
                    }
                    changed()
                } catch (e: IOException) {
                    seen.remove(file)
                    setProblems(file, listOf(problem("Cannot read the story: ${e.message}")))
                }
            }
        }
        val removed = (seen.keys + problems.keys.filter { it.fileName.toString().endsWith(StoryFormat.FILE_SUFFIX) })
            .filter { it.parent == folder.localDir || it.parent == folder.sharedDir }.toSet() - present
        for (file in removed) {
            seen.remove(file)
            accepted.remove(file)
            acceptance.forget(file)
            setProblems(file, emptyList())
        }
        if (removed.isNotEmpty()) changed()
        folder.refreshPendingIndex()
    }

    private suspend fun receive(folder: StoryFolder, file: Path, text: String) {
        val id = file.fileName.toString().removeSuffix(StoryFormat.FILE_SUFFIX)
        val json = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: SerializationException) { null }
        if (json == null) return setProblems(file, listOf(problem("The file must contain a JSON story object.")))
        if (!StoryFolder.isValidId(id) || (json["id"] as? JsonPrimitive)?.content != id) {
            return setProblems(file, listOf(problem("Use a <yyyy-MM-dd>-<slug>.story.json filename and the same id inside the story.")))
        }
        val decoded = when (val result = StoryFormat.decode(text)) {
            is StoryFormat.Decoded.Invalid -> return setProblems(file, listOf(problem(result.reason)))
            is StoryFormat.Decoded.Valid -> result.story
        }
        val previous = accepted[file]
        // MCP plays in its receiving IDE only.
        if (decoded.delivery == StoryFormat.MCP_DELIVERY) {
            accepted[file] = decoded
            previous?.let { threadsChanged(folder, file, it, decoded) }
            return setProblems(file, emptyList())
        }
        // A previously accepted file is saved history, even when its code changed while the IDE
        // was closed. Preserve its reference code and let normal anchor resolution show drift.
        if (previous == null && file !in problems && acceptance.contains(file, decoded)) {
            accepted[file] = decoded
            return setProblems(file, emptyList())
        }
        // Comment threads change in an accepted story without a new delivery, even after its code moved on.
        if (file !in problems && previous != null && StoryThreads.strip(previous) == StoryThreads.strip(decoded)) {
            accepted[file] = decoded
            return threadsChanged(folder, file, previous, decoded)
        }
        val service = CodeStoryService.getInstance(project)
        val checked = when (val result = service.validateIncomingStory(json, folder)) {
            is ValidationResult.Invalid -> return setProblems(file, result.errors)
            is ValidationResult.Valid -> result.story
        }
        // Validate even anchor-only changes, but do not restart playback for the plugin's rewrites.
        if (file !in problems && previous != null && sameDelivery(previous, decoded)) {
            accepted[file] = decoded
            acceptance.anchorsUpdated(file, previous, decoded)
            return
        }
        val (resolved, _) = StoryChangeInfo.getInstance(project).resolveBase(folder.root, checked)
        val normalized = StoryThreads.carryOver(decoded, resolved.copy(createdAt = decoded.createdAt, git = decoded.git))
        val encoded = StoryFormat.encode(normalized, id)
        val size = encoded.toByteArray(Charsets.UTF_8).size
        if (size > StoryLimits.MAX_FILE_BYTES) return setProblems(file, listOf(ReportItem.storyTooLarge(size)))
        val stored = folder.acceptDrop(file, text, normalized) ?: return
        seen[file] = hash(encoded)
        val replacing = accepted.put(file, normalized) != null
        acceptance.remember(stored)
        setProblems(file, emptyList())
        service.storyDelivered(stored, replacing)
    }

    /** Comments changed in the file, in this IDE or in another one: a playing story shows them and stays at its step. */
    private fun threadsChanged(folder: StoryFolder, file: Path, previous: Story, current: Story) {
        if (StoryThreads.all(previous) == StoryThreads.all(current)) return
        val stored = StoredStory(file.fileName.toString().removeSuffix(StoryFormat.FILE_SUFFIX), folder.root, file, current, Files.getLastModifiedTime(file))
        ApplicationManager.getApplication().invokeLater({ StoryPlayer.getInstance(project).threadsChanged(stored) }, ModalityState.nonModal(), project.disposed)
    }

    /**
     * Plugin-owned anchors and snapshots can differ between IDEs with different unsaved edits.
     * Comment threads change without a new delivery.
     */
    private fun sameDelivery(first: Story, second: Story): Boolean {
        fun content(story: Story) = StoryThreads.strip(story).let { stripped ->
            stripped.copy(steps = stripped.steps.map { step ->
                step.copy(location = step.location?.let { location ->
                    if (location.rangeText == null) location else location.copy(startLine = 1, endLine = 1, anchorText = null, rangeText = "")
                })
            })
        }
        return content(first) == content(second)
    }

    private fun setProblems(path: Path, errors: List<ReportItem>) {
        // Path implements Iterable<Path>; `problems - path` would remove its individual components.
        val next = if (errors.isEmpty()) problems - setOf(path) else problems + (path to errors)
        if (next == problems) return
        problems = next
        changed()
    }

    private fun changed() {
        if (!project.isDisposed) project.messageBus.syncPublisher(StoryListListener.TOPIC).storiesChanged(null)
    }

    override fun dispose() {
        job?.cancel()
        watcher?.close()
    }

    companion object {
        fun getInstance(project: Project): StoryFileWatcher = project.service()
        private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun problem(message: String) = ReportItem(null, ErrorCode.INVALID_VALUE, message, "Fix the file in .codestory/local and save it again.")
    }
}
