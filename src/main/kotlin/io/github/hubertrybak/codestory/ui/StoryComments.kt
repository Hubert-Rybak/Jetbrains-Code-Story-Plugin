package io.github.hubertrybak.codestory.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import io.github.hubertrybak.codestory.CodeStoryService
import io.github.hubertrybak.codestory.StoryDraft
import io.github.hubertrybak.codestory.StoryReaderState
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryThread
import java.awt.datatransfer.StringSelection

/**
 * The reader's comment actions in the tool window. Drafts change at once, in IDE storage. Story
 * files change in [background]; the tool window reloads when a file changed.
 */
class StoryComments(
    private val project: Project,
    private val background: (() -> Unit) -> Unit,
    private val draftsChanged: () -> Unit,
) {
    private val reader get() = project.service<StoryReaderState>()
    private val service get() = CodeStoryService.getInstance(project)

    fun drafts(stored: StoredStory): List<StoryDraft> = reader.drafts(stored.key)

    fun save(draft: StoryDraft) {
        reader.saveDraft(draft)
        draftsChanged()
    }

    fun delete(draft: StoryDraft) {
        reader.deleteDrafts(listOf(draft.id))
        draftsChanged()
    }

    fun submit(stored: StoredStory) = background { service.submitRound(stored) }

    fun withdraw(stored: StoredStory, thread: StoryThread) = background {
        if (!service.withdraw(stored, thread.id)) notify("An agent already fetched this comment. It stays in the story until the agent replies.")
    }

    fun resolve(stored: StoredStory, thread: StoryThread) = background { service.resolve(stored, thread.id) }

    fun copy(text: String) = CopyPasteManager.getInstance().setContents(StringSelection(text))

    private fun notify(message: String) = ApplicationManager.getApplication().invokeLater({
        NotificationGroupManager.getInstance().getNotificationGroup(StoryListPanel.NOTIFICATION_GROUP)
            .createNotification(message, NotificationType.INFORMATION).notify(project)
    }, ModalityState.nonModal(), project.disposed)
}
