package io.github.hubertrybak.codestory

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.hubertrybak.codestory.core.ChangedLines
import io.github.hubertrybak.codestory.core.FileLookup
import io.github.hubertrybak.codestory.core.LineChange
import io.github.hubertrybak.codestory.core.Location
import io.github.hubertrybak.codestory.core.ReportItem
import io.github.hubertrybak.codestory.core.StepChange
import io.github.hubertrybak.codestory.core.StepStatus
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.Story
import io.github.hubertrybak.codestory.core.StoryFiles
import io.github.hubertrybak.codestory.core.TextFiles
import io.github.hubertrybak.codestory.core.WarningCode
import io.github.hubertrybak.codestory.ui.StoryListPanel
import java.nio.file.Path

/** A file at a base commit: its path then, relative to the repository root, and its content. */
private class BaseFile(val path: String, val content: ByteArray)

/**
 * The change information of a story with a base: [commit] is the base commit, or `null` when Git
 * does not know [baseRef]. Steps are found by their stable IDs, so a newer version of the story
 * never shows the badge of another step.
 */
class StoryChanges(val baseRef: String, val commit: String?, private val steps: Map<String, StepChange>) {
    fun step(id: String?): StepChange? = id?.let(steps::get)
}

/**
 * Change information for stories about a change: what each step added or modified since the
 * story's base commit, and the IDE diff viewer for a step. Needs the Git plugin; without it,
 * stories show no change information.
 */
@Service(Service.Level.PROJECT)
class StoryChangeInfo(private val project: Project) {

    /** The story to save, with its base as a full commit SHA, and a warning when Git does not know the base. Call in the background. */
    fun resolveBase(root: Path, story: Story): Pair<Story, List<ReportItem>> {
        val baseRef = story.baseRef ?: return story to emptyList()
        val git = git() ?: return story to emptyList()
        val commit = git.resolveCommit(project, root, baseRef)
            ?: return story to listOf(ReportItem(
                null, WarningCode.UNKNOWN_BASE_REF.code,
                "The story `baseRef` `$baseRef` is not a commit in the Git repository at `$root`.",
                "Set `baseRef` to the commit before the change, or to `HEAD` for uncommitted work. The story shows no change information until Git knows the commit.",
            ))
        return story.copy(baseRef = commit) to emptyList()
    }

    /**
     * The change of each step since the story's base, or `null` for a story without a base or
     * without the Git plugin. Compares the base with the text that the IDE shows, including
     * unsaved edits. Runs Git, so call it in the background.
     */
    fun changes(stored: StoredStory): StoryChanges? {
        val baseRef = stored.story.baseRef ?: return null
        val git = git() ?: return null
        val commit = git.resolveCommit(project, stored.root, baseRef)
            ?: return StoryChanges(baseRef, null, emptyMap())
        val files = CodeStoryService.getInstance(project).ideFiles(stored.root)
        val base = BaseFiles(git, stored.root, commit)
        // Several steps usually point at the same file.
        val diffs = HashMap<String, Pair<List<String>, List<LineChange>>?>()
        val steps = stored.story.steps.withIndex().mapNotNull { (index, step) ->
            val id = step.id ?: return@mapNotNull null
            val location = step.location ?: return@mapNotNull null
            // A lost step has no code to compare.
            if (stored.status(index) == StepStatus.LOST) return@mapNotNull null
            val diff = if (location.file in diffs) diffs[location.file] else diff(stored.root, location.file, files, base).also { diffs[location.file] = it }
            val (lines, changes) = diff ?: return@mapNotNull null
            ChangedLines.stepChange(changes, lines, location.startLine, location.endLine)?.let { id to it }
        }
        return StoryChanges(baseRef, commit, steps.toMap())
    }

    /** The current lines of [file] and their changes since the base, or `null` when the file cannot be compared. */
    private fun diff(root: Path, file: String, files: StoryFiles, base: BaseFiles): Pair<List<String>, List<LineChange>>? {
        val current = (files.lookup(file) as? FileLookup.Found)?.text ?: return null
        val lines = TextFiles.lines(current)
        val baseFile = base.get(file) ?: return lines to ChangedLines.newFile(lines)
        val changes = compare(text(baseFile, LocalFileSystem.getInstance().findFileByNioFile(root.resolve(file))), current) ?: return null
        return lines to changes
    }

    /** The files of a story at its base commit. A file that the commit does not have can be a renamed file. */
    private inner class BaseFiles(private val git: StoryGitMetadata, private val root: Path, private val commit: String) {
        // Finding renames compares the whole working tree, so it runs once, and only when a file is missing.
        private val renames by lazy { git.renames(project, root, commit) }

        fun get(file: String): BaseFile? = git.fileAt(project, root, commit, file)?.let { BaseFile(file, it) }
            ?: renames[file]?.let { old -> git.fileAt(project, root, commit, old)?.let { BaseFile(old, it) } }
    }

    /**
     * Opens the IDE diff viewer for step [index]: its file at the base commit against the current
     * file, scrolled to the step's lines. Reads Git in the background, then shows the diff.
     */
    fun showDiff(stored: StoredStory, index: Int) {
        val location = stored.story.steps.getOrNull(index)?.location ?: return
        val baseRef = stored.story.baseRef ?: return
        val git = git() ?: return
        AppExecutorUtil.getAppExecutorService().execute {
            val commit = git.resolveCommit(project, stored.root, baseRef)
            val file = StoryNavigationFiles.find(stored.root, location.file)
            val base = if (commit == null || file == null) null else BaseFiles(git, stored.root, commit).get(location.file)
            val baseText = base?.let { text(it, file) }
            ApplicationManager.getApplication().invokeLater(
                {
                    // Git ran in the background; a link may have changed before the viewer opens.
                    val currentFile = file?.let { StoryNavigationFiles.find(stored.root, location.file) }
                    if (commit == null || currentFile == null) notify("Code Story cannot show the diff of ${location.file}: ${if (commit == null) "Git does not know the commit $baseRef" else "the file is missing or outside the repository"}.")
                    else DiffManager.getInstance().showDiff(project, diffRequest(location, currentFile, commit, base?.path, baseText))
                },
                ModalityState.nonModal(),
                project.disposed,
            )
        }
    }

    private fun diffRequest(location: Location, file: VirtualFile, commit: String, basePath: String?, baseText: String?): SimpleDiffRequest {
        val factory = DiffContentFactory.getInstance()
        val short = commit.take(SHORT_COMMIT)
        val base = if (baseText == null) factory.createEmpty() else factory.create(project, baseText, file)
        val baseTitle = when (basePath) {
            null -> "Base $short (no file)"
            location.file -> "Base $short"
            else -> "Base $short ($basePath)"
        }
        return SimpleDiffRequest("${location.file} since $short", base, factory.create(project, file), baseTitle, "Current").apply {
            putUserData(DiffUserDataKeys.SCROLL_TO_LINE, com.intellij.openapi.util.Pair.create(Side.RIGHT, location.startLine - 1))
        }
    }

    /** The changed lines between the two texts, like `git diff`; `null` when the files are too different to compare. */
    private fun compare(base: String, current: CharSequence): List<LineChange>? = try {
        ComparisonManager.getInstance()
            .compareLines(base, normalized(current.toString()), ComparisonPolicy.DEFAULT, EmptyProgressIndicator())
            .map { LineChange(it.startLine1, it.endLine1, it.startLine2, it.endLine2) }
    } catch (_: DiffTooBigException) {
        null
    }

    /** The base content in the encoding of the current file. */
    private fun text(base: BaseFile, file: VirtualFile?): String =
        normalized(if (file == null) String(base.content, Charsets.UTF_8) else LoadTextUtil.getTextByBinaryPresentation(base.content, file).toString())

    /** `\n` line breaks and no byte order mark, as the editor shows the text. */
    private fun normalized(text: String): String = StringUtil.convertLineSeparators(text.removePrefix("\uFEFF"))

    private fun notify(message: String) = NotificationGroupManager.getInstance().getNotificationGroup(StoryListPanel.NOTIFICATION_GROUP)
        .createNotification(message, NotificationType.WARNING).notify(project)

    private fun git(): StoryGitMetadata? = StoryGitMetadata.EP.extensionList.firstOrNull()

    companion object {
        private const val SHORT_COMMIT = 10

        fun getInstance(project: Project): StoryChangeInfo = project.service()
    }
}
