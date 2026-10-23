package io.github.hubertrybak.codestory.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import io.github.hubertrybak.codestory.StoryNavigationFiles
import io.github.hubertrybak.codestory.core.Location
import io.github.hubertrybak.codestory.core.StoredStory

/**
 * Opens the IDE diff viewer for the proposal of step [index]: the current file beside the same file
 * with the proposed code in place of the step's lines. The plugin never writes the proposal into
 * the file: the reader applies the change in the diff viewer, or asks the agent to apply it.
 */
fun showProposalDiff(project: Project, stored: StoredStory, index: Int) {
    val step = stored.story.steps.getOrNull(index) ?: return
    val location = step.location ?: return
    val code = step.proposedCode ?: return
    val file = StoryNavigationFiles.find(stored.root, location.file)
    val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
    if (file == null || document == null || location.endLine > document.lineCount) {
        NotificationGroupManager.getInstance().getNotificationGroup(StoryListPanel.NOTIFICATION_GROUP)
            .createNotification("Code Story cannot show the proposal: ${location.file} is missing or has fewer lines than the step.", NotificationType.WARNING)
            .notify(project)
        return
    }
    val factory = DiffContentFactory.getInstance()
    val request = SimpleDiffRequest(
        "Proposal: ${step.title}", factory.create(project, file), factory.create(project, proposedText(document, location, code), file),
        "Current", "With the proposal",
    )
    request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, com.intellij.openapi.util.Pair.create(Side.RIGHT, location.startLine - 1))
    DiffManager.getInstance().showDiff(project, request)
}

/** The text of [document] with [code] in place of the lines of [location]. An empty proposal removes the lines and one line break. */
internal fun proposedText(document: Document, location: Location, code: String): String {
    val text = document.immutableCharSequence
    val start = document.getLineStartOffset(location.startLine - 1)
    val end = document.getLineEndOffset(location.endLine - 1)
    if (code.isNotEmpty()) return text.substring(0, start) + code + text.substring(end)
    val (from, to) = when {
        location.endLine < document.lineCount -> start to document.getLineStartOffset(location.endLine)
        location.startLine > 1 -> document.getLineEndOffset(location.startLine - 2) to end
        else -> start to end
    }
    return text.substring(0, from) + text.substring(to)
}
