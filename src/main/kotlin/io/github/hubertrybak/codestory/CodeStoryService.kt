package io.github.hubertrybak.codestory

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.messages.Topic
import io.github.hubertrybak.codestory.core.ReportItem
import io.github.hubertrybak.codestory.core.ErrorCode
import io.github.hubertrybak.codestory.core.RootStoryFiles
import io.github.hubertrybak.codestory.core.ShowReport
import io.github.hubertrybak.codestory.core.StoredStory
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
    /** [shownStoryId] is the story that an agent just showed, if any. */
    fun storiesChanged(shownStoryId: String?)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<StoryListListener> = Topic(StoryListListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/** The stories of a project: the handler of the agent API and the source of the tool window. */
@Service(Service.Level.PROJECT)
class CodeStoryService(private val project: Project) {

    /** The story that an agent showed last. The tool window selects it when it opens. */
    @Volatile
    var lastShownStoryId: String? = null
        private set

    fun storyFolder(): StoryFolder {
        val basePath = checkNotNull(project.basePath) { "The project has no folder: $project" }
        return StoryFolder.forProject(Path.of(basePath))
    }

    /**
     * Handles `code_story_show`: checks the [story] JSON against the project files and saves it
     * in `.codestory/local/` when it has no errors. A rejected story saves nothing.
     * Reads and writes files, so call it on a background dispatcher.
     */
    suspend fun show(story: JsonElement, storyId: String? = null): ShowReport {
        val folder = storyFolder()
        if (storyId != null && !StoryFolder.isValidId(storyId)) return ShowReport.rejected(listOf(
            ReportItem(null, ErrorCode.INVALID_STORY_ID, "The story ID is not valid.", "Use an ID returned by code_story_list."),
        ))
        val unsaved = unsavedTexts()
        val files = RootStoryFiles(folder.root) { path -> unsaved[realPath(path)] ?: TextFiles.readDisk(path) }
        val checked = when (val result = StoryValidator(files).validate(story)) {
            is ValidationResult.Invalid -> return ShowReport.rejected(result.errors)
            is ValidationResult.Valid -> result.story
        }
        val warnings = StoryWritingChecks(CodeStorySettings.getInstance().state.optionalStyleHints).check(checked)
        val git = currentGit(folder.root)
        return when (val saved = folder.save(checked, Instant.now(), git, storyId)) {
            is StoryFolder.SaveResult.TooLarge -> ShowReport.rejected(listOf(ReportItem.storyTooLarge(saved.bytes)), warnings)
            is StoryFolder.SaveResult.NotFound -> ShowReport.rejected(listOf(
                ReportItem(null, ErrorCode.STORY_NOT_FOUND, "The story `${saved.id}` was not found.", "Call code_story_list to find the current story ID."),
            ), warnings)
            is StoryFolder.SaveResult.Saved -> {
                lastShownStoryId = saved.id
                project.messageBus.syncPublisher(StoryListListener.TOPIC).storiesChanged(saved.id)
                startPlayback(StoredStory(saved.id, folder.root, saved.file, saved.story, Files.getLastModifiedTime(saved.file)), storyId != null)
                ShowReport.accepted(saved.id, folder.relativePath(saved.file), warnings)
            }
        }
    }

    /** The saved stories, newest first. */
    fun stories(): List<StoredStory> = storyFolder().list { file, reason -> LOG.warn("Code Story skipped $file: $reason") }

    /** Full saved story, including plugin-owned metadata and stable step IDs. */
    fun get(storyId: String): JsonObject? = storyFolder().get(storyId)?.let {
        Json.parseToJsonElement(StoryFormat.encode(it.story, it.id)).jsonObject
    }

    suspend fun list(): List<StoryListEntry> {
        val folder = storyFolder()
        val unsaved = unsavedTexts()
        val files = RootStoryFiles(folder.root) { path -> unsaved[realPath(path)] ?: TextFiles.readDisk(path) }
        return stories().map { stored ->
            val input = Json.parseToJsonElement(StoryFormat.encode(stored.story, stored.id))
            val result = StoryValidator(files).validate(input)
            val lost = if (result is ValidationResult.Invalid) result.errors.mapNotNull { it.step }.distinct().size else 0
            StoryListEntry(stored.id, stored.story.title, stored.story.type, stored.date, stored.story.git.branch,
                stored.folder, if (lost == 0) "ok" else "lost", lost)
        }
    }

    /** The current repository state; unavailable Git fields stay null. Call in the background. */
    fun currentGit(root: Path): StoryGit = StoryGitMetadata.EP.extensionList.firstOrNull()?.snapshot(project, root) ?: StoryGit()

    /** Documents with unsaved edits, by real path: for these files, the IDE shows other text than the disk has. */
    private suspend fun unsavedTexts(): Map<Path, CharSequence> {
        val documents = readAction {
            val manager = FileDocumentManager.getInstance()
            manager.unsavedDocuments.mapNotNull { document ->
                val file = manager.getFile(document) ?: return@mapNotNull null
                file.fileSystem.getNioPath(file)?.let { it to document.immutableCharSequence }
            }
        }
        return documents.associate { (path, text) -> realPath(path) to text }
    }

    private fun realPath(path: Path): Path = try {
        path.toRealPath()
    } catch (_: IOException) {
        path.toAbsolutePath().normalize()
    }

    private fun startPlayback(stored: StoredStory, replacing: Boolean) {
        ApplicationManager.getApplication().invokeLater(
            {
                val player = StoryPlayer.getInstance(project)
                if (replacing && player.currentStory?.id == stored.id) player.reload(stored)
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

    companion object {
        const val TOOL_WINDOW_ID = "Code Story"
        private val LOG = logger<CodeStoryService>()

        fun getInstance(project: Project): CodeStoryService = project.service()
    }
}
