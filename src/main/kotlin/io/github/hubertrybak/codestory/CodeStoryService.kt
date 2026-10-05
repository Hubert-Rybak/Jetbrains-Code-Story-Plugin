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
import com.intellij.util.messages.Topic
import io.github.hubertrybak.codestory.core.ReportItem
import io.github.hubertrybak.codestory.core.RootStoryFiles
import io.github.hubertrybak.codestory.core.ShowReport
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryFolder
import io.github.hubertrybak.codestory.core.StoryValidator
import io.github.hubertrybak.codestory.core.TextFiles
import io.github.hubertrybak.codestory.core.ValidationResult
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.nio.file.Path
import java.time.LocalDate

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
    suspend fun show(story: JsonElement): ShowReport {
        val folder = storyFolder()
        val unsaved = unsavedTexts()
        val files = RootStoryFiles(folder.root) { path -> unsaved[realPath(path)] ?: TextFiles.readDisk(path) }
        val checked = when (val result = StoryValidator(files).validate(story)) {
            is ValidationResult.Invalid -> return ShowReport.rejected(result.errors)
            is ValidationResult.Valid -> result.story
        }
        return when (val saved = folder.save(checked, LocalDate.now())) {
            is StoryFolder.SaveResult.TooLarge -> ShowReport.rejected(listOf(ReportItem.storyTooLarge(saved.bytes)))
            is StoryFolder.SaveResult.Saved -> {
                lastShownStoryId = saved.id
                project.messageBus.syncPublisher(StoryListListener.TOPIC).storiesChanged(saved.id)
                showToolWindow()
                ShowReport.accepted(saved.id, folder.relativePath(saved.file))
            }
        }
    }

    /** The saved stories, newest first. */
    fun stories(): List<StoredStory> = storyFolder().list { file, reason -> LOG.warn("Code Story skipped $file: $reason") }

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

    private fun showToolWindow() {
        ApplicationManager.getApplication().invokeLater(
            { ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show() },
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
