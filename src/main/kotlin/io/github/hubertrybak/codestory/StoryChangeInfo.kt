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
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.hubertrybak.codestory.core.FileLookup
import io.github.hubertrybak.codestory.core.LineChange
import io.github.hubertrybak.codestory.core.Location
import io.github.hubertrybak.codestory.core.StepChange
import io.github.hubertrybak.codestory.core.StepChanges
import io.github.hubertrybak.codestory.core.StepStatus
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryFiles
import io.github.hubertrybak.codestory.core.TextFiles
import io.github.hubertrybak.codestory.ui.StoryListPanel
import java.io.IOException
import java.nio.file.Path

/** What the reader sees about the changes of a story since its base commit. */
sealed interface StoryChanges {
    /** The story has no base, or the Git plugin is not available: no change information. */
    data object None : StoryChanges

    /** Git does not know the base commit, so there are no badges and no diffs. */
    data object UnknownBase : StoryChanges

    /** [steps] has the change of each step by index: `null` for unchanged, text-only and lost steps. */
    class Known(val commit: String, val steps: List<StepChange?>) : StoryChanges
}

/**
 * Change information for stories about a change. It needs the Git plugin: the added or modified
 * lines of each step since the story's `baseRef`, and the IDE diff viewer at a step.
 */
@Service(Service.Level.PROJECT)
class StoryChangeInfo(private val project: Project) {
    /** The content of a file at a commit never changes. Files that are not in the commit are read again. */
    private val baseFiles = object : LinkedHashMap<List<Any>, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any>, ByteArray>) = size > MAX_CACHED_FILES
    }

    private val git: StoryGitMetadata? get() = StoryGitMetadata.EP.extensionList.firstOrNull()

    /**
     * The commit that [baseRef] names in the repository at [root]: the full SHA, or `null` when Git
     * does not know it. Without the Git plugin, [baseRef] stays as it is. Call in the background.
     */
    fun baseCommit(root: Path, baseRef: String): String? {
        val git = git ?: return baseRef
        return git.commit(project, root, baseRef)
    }

    /**
     * The change of each step of [stored] since its base, with the current code from [files].
     * [stored] must have checked anchors, so that its lines are current. Runs Git, so call it in the background.
     */
    fun changes(stored: StoredStory, files: StoryFiles): StoryChanges {
        val baseRef = stored.story.baseRef ?: return StoryChanges.None
        val git = git ?: return StoryChanges.None
        val commit = git.commit(project, stored.root, baseRef) ?: return StoryChanges.UnknownBase
        val lineChanges = HashMap<String, List<LineChange>?>()
        val steps = stored.story.steps.mapIndexed { index, step ->
            // The stored lines of a lost step do not show its code.
            val location = step.location?.takeIf { stored.status(index) != StepStatus.LOST } ?: return@mapIndexed null
            val changes = if (location.file in lineChanges) lineChanges[location.file] else {
                lineChanges(git, stored.root, commit, location.file, files).also { lineChanges[location.file] = it }
            }
            changes?.let { StepChanges.of(location.startLine, location.endLine, it) }
        }
        return StoryChanges.Known(commit, steps)
    }

    /**
     * Opens the IDE diff viewer for the file of step [index]: the base revision against the current
     * file, scrolled to the lines of the step. Reads Git in the background. Call on the EDT.
     */
    fun showDiff(stored: StoredStory, index: Int) {
        val location = stored.story.steps[index].location ?: return
        val baseRef = stored.story.baseRef ?: return
        val git = git ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val commit = git.commit(project, stored.root, baseRef)
            val base = commit?.let { baseFile(git, stored.root, it, location.file) }
            ApplicationManager.getApplication().invokeLater(
                { if (commit == null) warn("Git does not know the diff base of this story.") else showDiff(stored.root, location, commit, base) },
                ModalityState.nonModal(),
                project.disposed,
            )
        }
    }

    private fun showDiff(root: Path, location: Location, commit: String, base: ByteArray?) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(location.file))
            ?.takeUnless { it.isDirectory } ?: return warn("Code Story cannot open ${location.file}.")
        val factory = DiffContentFactory.getInstance()
        // A file that is new since the base has an empty left side.
        val baseContent = try {
            base?.let { factory.createFromBytes(project, it, file) } ?: factory.createEmpty()
        } catch (_: IOException) {
            return warn("Code Story cannot read ${location.file} at the diff base.")
        }
        val short = commit.take(SHORT_COMMIT)
        val request = SimpleDiffRequest("${location.file} since $short", baseContent, factory.create(project, file), "Base $short", "Current")
        request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, Pair.create(Side.RIGHT, location.startLine - 1))
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun lineChanges(git: StoryGitMetadata, root: Path, commit: String, file: String, files: StoryFiles): List<LineChange>? {
        val current = (files.lookup(file) as? FileLookup.Found)?.text ?: return null
        // Story code is read as UTF-8, like the files on disk.
        val base = baseFile(git, root, commit, file)?.toString(Charsets.UTF_8).orEmpty()
        return try {
            ComparisonManager.getInstance().compareLines(lines(base), lines(current), ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
                .map { LineChange(it.startLine1, it.endLine1, it.startLine2, it.endLine2) }
        } catch (_: DiffTooBigException) {
            null
        }
    }

    private fun baseFile(git: StoryGitMetadata, root: Path, commit: String, path: String): ByteArray? {
        val key = listOf(root, commit, path)
        synchronized(baseFiles) { baseFiles[key] }?.let { return it }
        return git.fileAt(project, root, commit, path)?.also { synchronized(baseFiles) { baseFiles[key] = it } }
    }

    /** Notifications show HTML, and story paths can come from other people. */
    private fun warn(message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(StoryListPanel.NOTIFICATION_GROUP)
            .createNotification(StringUtil.escapeXmlEntities(message), NotificationType.WARNING).notify(project)
    }

    companion object {
        private const val MAX_CACHED_FILES = 64
        private const val SHORT_COMMIT = 8

        fun getInstance(project: Project): StoryChangeInfo = project.service()

        /** The lines of [text] with `\n` between them, without a byte order mark or a final line break. */
        private fun lines(text: CharSequence): String = TextFiles.lines(text).joinToString("\n").removePrefix("﻿")
    }
}
