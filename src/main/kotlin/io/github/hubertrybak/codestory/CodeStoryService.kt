package io.github.hubertrybak.codestory

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import io.github.hubertrybak.codestory.core.ReportItem
import io.github.hubertrybak.codestory.core.ErrorCode
import io.github.hubertrybak.codestory.core.RootStoryFiles
import io.github.hubertrybak.codestory.core.ShowReport
import io.github.hubertrybak.codestory.core.StepStatus
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryAnchors
import io.github.hubertrybak.codestory.core.StoryFiles
import io.github.hubertrybak.codestory.core.StoryFolder
import io.github.hubertrybak.codestory.core.StoryFormat
import io.github.hubertrybak.codestory.core.StoryListEntry
import io.github.hubertrybak.codestory.core.StoryWritingChecks
import io.github.hubertrybak.codestory.core.StoryGit
import io.github.hubertrybak.codestory.core.StoryValidator
import io.github.hubertrybak.codestory.core.TextFiles
import io.github.hubertrybak.codestory.core.ValidationResult
import io.github.hubertrybak.codestory.ui.StoryPlayer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant

/** Tells the tool window that the stories changed. */
fun interface StoryListListener {
    /** [shownStoryKey] identifies the repository and story that an agent just showed, if any. */
    fun storiesChanged(shownStoryKey: String?)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<StoryListListener> = Topic(StoryListListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/** The stories of a project: the handler of the agent API and the source of the tool window. */
@Service(Service.Level.PROJECT)
class CodeStoryService(private val project: Project) : Disposable {

    /** Writes moved anchors in the order of the checks. */
    private val anchorWriter = AppExecutorUtil.createBoundedApplicationPoolExecutor("Code Story anchors", 1)

    /** The story that an agent showed last. The tool window selects it when it opens. */
    @Volatile
    var lastShownStoryId: String? = null
        private set

    @Volatile
    var lastShownStoryKey: String? = null
        private set

    fun storyFolders(): List<StoryFolder> = StoryRepositories(project).folders()

    fun storyFolder(): StoryFolder {
        val basePath = checkNotNull(project.basePath) { "The project has no folder: $project" }
        return StoryFolder.forProject(Path.of(basePath))
    }

    /**
     * Handles `code_story_show`: checks the [story] JSON against the project files and saves it
     * in `.codestory/local/` when it has no errors. A rejected story saves nothing.
     * Reads and writes files, so call it on a background dispatcher.
     */
    suspend fun show(story: JsonElement, storyId: String? = null, repositoryPath: String? = null): ShowReport {
        if (storyId != null && !StoryFolder.isValidId(storyId)) return ShowReport.rejected(listOf(
            ReportItem(null, ErrorCode.INVALID_STORY_ID, "The story ID is not valid.", "Use an ID returned by code_story_list."),
        ))
        val structure = when (val result = StoryValidator(null).validate(story)) {
            is ValidationResult.Invalid -> return ShowReport.rejected(result.errors, corrections = result.corrections)
            is ValidationResult.Valid -> result.story
        }
        val selection = StoryRepositories(project).select(structure, storyId, repositoryPath)
        selection.error?.let { return ShowReport.rejected(listOf(it)) }
        val folder = checkNotNull(selection.folder)
        val checked = when (val result = validateIncomingStory(story, folder)) {
            is ValidationResult.Invalid -> return ShowReport.rejected(result.errors, corrections = result.corrections)
            is ValidationResult.Valid -> result
        }
        // A story saves its base as a commit SHA, so that `HEAD` still names the base after a commit.
        val (story, baseWarnings) = StoryChangeInfo.getInstance(project).resolveBase(folder.root, checked.story)
        val warnings = StoryWritingChecks(CodeStorySettings.getInstance().state.optionalStyleHints).check(story) + baseWarnings
        val git = currentGit(folder.root)
        return when (val saved = folder.save(story.copy(delivery = StoryFormat.MCP_DELIVERY), Instant.now(), git, storyId)) {
            is StoryFolder.SaveResult.TooLarge -> ShowReport.rejected(listOf(ReportItem.storyTooLarge(saved.bytes)), warnings, checked.corrections)
            is StoryFolder.SaveResult.NotFound -> ShowReport.rejected(listOf(
                ReportItem(null, ErrorCode.STORY_NOT_FOUND, "The story `${saved.id}` was not found.", "Call code_story_list to find the current story ID."),
            ), warnings, checked.corrections)
            is StoryFolder.SaveResult.Saved -> {
                storyDelivered(StoredStory(saved.id, folder.root, saved.file, saved.story, Files.getLastModifiedTime(saved.file)), storyId != null)
                ShowReport.accepted(saved.id, folder.relativePath(saved.file), warnings, checked.corrections)
            }
        }
    }

    /** The same arrival validation for MCP and file drops, including unsaved editor text. */
    suspend fun validateIncomingStory(json: JsonElement, folder: StoryFolder = storyFolder()): ValidationResult {
        val structure = when (val result = StoryValidator(null).validate(json)) {
            is ValidationResult.Invalid -> return result
            is ValidationResult.Valid -> result.story
        }
        StoryRepositories(project).select(structure, null, folder.root.toString()).error?.let {
            return ValidationResult.Invalid(listOf(it))
        }
        val (documents, live) = readAction { openDocuments() to AnchorTracker.getInstance(project).snapshot() }
        val files = ideFiles(folder.root, documents.associate { (path, text) -> realPath(path) to text } + live?.texts.orEmpty())
        return StoryValidator(files).validate(json)
    }

    fun storyDelivered(stored: StoredStory, replacing: Boolean) {
        project.service<StoryReaderState>().opened(stored)
        lastShownStoryId = stored.id
        lastShownStoryKey = stored.key
        project.messageBus.syncPublisher(StoryListListener.TOPIC).storiesChanged(stored.key)
        startPlayback(stored, replacing)
    }

    /**
     * The saved stories, newest first, with the code of their steps found again: a story opens.
     * Moved anchors are written back to local stories. Reads files, so call it in the background.
     */
    fun stories(): List<StoredStory> {
        val live = AnchorTracker.getInstance(project).snapshot()
        val reader = project.service<StoryReaderState>()
        val rejected = StoryFileWatcher.getInstance(project).problems.keys
        return storyFolders().flatMap { folder ->
            val stored = folder.list { file, reason -> LOG.warn("Code Story skipped $file: $reason") }.filter { it.file !in rejected }
            val files = ideFiles(folder.root, live)
            stored.map { resolve(folder, it, files, live).withNewMarker(reader.isNew(it)) }
        }.sortedWith(compareByDescending<StoredStory> { it.story.createdAt }.thenByDescending { it.id }.thenBy { it.root.toString() })
    }

    /** Full saved story, including plugin-owned metadata and stable step IDs, with its anchors checked. */
    fun get(storyId: String, repositoryPath: String? = null): JsonObject? {
        val matches = storyFolders().filter { repositoryPath == null || it.root.toString() == repositoryPath }
            .mapNotNull { it.get(storyId) }
        if (matches.size > 1) throw IOException("That story ID exists in several repositories. Pass repositoryPath from code_story_list.")
        val stored = matches.singleOrNull() ?: return null
        val folder = StoryFolder(stored.root)
        val live = AnchorTracker.getInstance(project).snapshot()
        val resolved = resolve(folder, stored, ideFiles(folder.root, live), live)
        return Json.parseToJsonElement(StoryFormat.encode(resolved.story, resolved.id)).jsonObject
    }

    fun list(): List<StoryListEntry> = stories().map(StoryListEntry::of)

    /** File operations run in the background; playback metadata is adjusted on the EDT. */
    fun setShared(stored: StoredStory, shared: Boolean) {
        val moved = StoryFileWatcher.getInstance(project).setShared(stored, shared)
        project.service<StoryReaderState>().opened(moved)
        ApplicationManager.getApplication().invokeLater({
            StoryPlayer.getInstance(project).relocated(moved)
        }, ModalityState.nonModal(), project.disposed)
        project.messageBus.syncPublisher(StoryListListener.TOPIC).storiesChanged(null)
    }

    fun delete(stored: StoredStory) {
        if (!StoryFolder(stored.root).delete(stored)) throw IOException("The story has moved or was already deleted. Refresh the list.")
        ApplicationManager.getApplication().invokeLater({
            val player = StoryPlayer.getInstance(project)
            if (player.currentStory?.key == stored.key) player.stop()
        }, ModalityState.nonModal(), project.disposed)
        project.messageBus.syncPublisher(StoryListListener.TOPIC).storiesChanged(null)
    }

    fun opened(stored: StoredStory) {
        if (project.service<StoryReaderState>().opened(stored) && stored.isNew) {
            project.messageBus.syncPublisher(StoryListListener.TOPIC).storiesChanged(null)
        }
    }

    /** The current repository state; unavailable Git fields stay null. Call in the background. */
    fun currentGit(root: Path): StoryGit = StoryGitMetadata.EP.extensionList.firstOrNull()?.snapshot(project, root) ?: StoryGit()

    /**
     * The files under [root] as the IDE shows them: the text of open documents, including unsaved
     * edits, before the text on disk. The documents of [live] markers give the text that their
     * lines refer to.
     */
    fun ideFiles(root: Path, live: LiveSnapshot? = AnchorTracker.getInstance(project).snapshot()): StoryFiles {
        val documents = ApplicationManager.getApplication().runReadAction(Computable { openDocuments() })
        return ideFiles(root, documents.associate { (path, text) -> realPath(path) to text } + live?.texts.orEmpty())
    }

    /**
     * Saves the anchors that a check moved: [resolved] is [stored] after the check. Only local
     * stories change; corrections for shared stories stay in memory. New lines, and steps that
     * become or stop being lost or changed, also refresh the story list. Writes in the background.
     */
    fun anchorsChecked(stored: StoredStory, resolved: StoredStory) {
        // The list shows lost and changed steps only, so it needs no refresh for moved ones.
        fun shown(story: StoredStory, index: Int) = story.status(index).takeIf { it == StepStatus.LOST || it == StepStatus.CODE_CHANGED }
        val unchanged = stored.story.steps.indices.all { shown(stored, it) == shown(resolved, it) }
        if (resolved.story == stored.story && unchanged) return
        anchorWriter.execute {
            if (resolved.story != stored.story) saveAnchors(StoryFolder(stored.root), stored, resolved)
            if (!project.isDisposed) project.messageBus.syncPublisher(StoryListListener.TOPIC).storiesChanged(null)
        }
    }

    private fun resolve(folder: StoryFolder, stored: StoredStory, files: StoryFiles, live: LiveSnapshot?): StoredStory {
        val ranges = live?.takeIf { it.storyKey == stored.key }?.ranges.orEmpty()
        val resolved = stored.resolved(StoryAnchors.resolve(stored.story, files, ranges))
        if (resolved.story != stored.story) saveAnchors(folder, stored, resolved)
        return resolved
    }

    private fun saveAnchors(folder: StoryFolder, stored: StoredStory, resolved: StoredStory) {
        try {
            folder.updateAnchors(stored.id, stored.story, resolved.story)
        } catch (e: IOException) {
            LOG.warn("Code Story cannot save the moved anchors of ${stored.id}", e)
        }
    }

    private fun ideFiles(root: Path, texts: Map<Path, CharSequence>): StoryFiles {
        // Many steps and stories point at the same files.
        val disk = HashMap<Path, CharSequence>()
        return RootStoryFiles(root) { path -> texts[realPath(path)] ?: disk.getOrPut(path) { TextFiles.readDisk(path) } }
    }

    /** Documents with unsaved edits or in an editor: for these files, the IDE can show other text than the disk has. */
    private fun openDocuments(): List<Pair<Path, CharSequence>> {
        val manager = FileDocumentManager.getInstance()
        val documents = (manager.unsavedDocuments.asSequence() + EditorFactory.getInstance().allEditors.asSequence().map { it.document }).distinct()
        return documents.mapNotNull { document ->
            val file = manager.getFile(document) ?: return@mapNotNull null
            file.fileSystem.getNioPath(file)?.let { it to document.immutableCharSequence }
        }.toList()
    }

    private fun realPath(path: Path): Path = AnchorTracker.realPath(path)

    private fun startPlayback(stored: StoredStory, replacing: Boolean) {
        ApplicationManager.getApplication().invokeLater(
            {
                val player = StoryPlayer.getInstance(project)
                if (replacing && player.currentStory?.key == stored.key) player.reload(stored)
                else player.restart(stored)
                ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
                if (CodeStorySettings.getInstance().state.bringToFront) {
                    WindowManager.getInstance().getFrame(project)?.let { IdeFocusManager.getInstance(project).toFront(it.rootPane) }
                }
            },
            ModalityState.nonModal(),
            project.disposed,
        )
    }

    override fun dispose() { anchorWriter.shutdownNow() }

    companion object {
        const val TOOL_WINDOW_ID = "Code Story"
        private val LOG = logger<CodeStoryService>()

        fun getInstance(project: Project): CodeStoryService = project.service()
    }
}
