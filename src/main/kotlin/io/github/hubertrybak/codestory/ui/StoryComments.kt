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
import io.github.hubertrybak.codestory.StoryReaderListener
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryThread
import java.awt.datatransfer.StringSelection

/**
 * The reader's comment actions. A posted comment goes into the story file at once. Until the file
 * has it, the comment stays in IDE storage, so a failed write keeps it as an unsent comment. Story
 * files change in [background]; the comments reload when a file changed.
 */
class StoryComments(
    private val project: Project,
    private val background: (() -> Unit) -> Unit = { action -> ApplicationManager.getApplication().executeOnPooledThread(action) },
    private val draftsChanged: () -> Unit = {},
) {
    private val reader get() = project.service<StoryReaderState>()
    private val service get() = CodeStoryService.getInstance(project)

    /** Comments that are not in the story file: withdrawn, failed, or still being written. */
    fun drafts(stored: StoredStory): List<StoryDraft> = reader.drafts(stored.key)

    fun isSending(draft: StoryDraft) = reader.isSending(draft)

    /** Writes [draft] into the story file now. Agents that listen pick it up at once. */
    fun post(stored: StoredStory, draft: StoryDraft) {
        reader.saveDraft(draft)
        reader.setSending(draft, true)
        changed()
        background {
            var posted = false
            try {
                posted = service.post(stored, listOf(draft)) > 0
            } catch (e: java.io.IOException) {
                notify("Code Story could not post the comment: ${e.message} It is kept as an unsent comment.")
            } finally {
                reader.setSending(draft, false)
                // A posted comment shows when its story reloads; any other comment shows as unsent.
                if (!posted) changed()
            }
        }
    }

    fun delete(draft: StoryDraft) {
        reader.deleteDrafts(listOf(draft.id))
        changed()
    }

    fun read(stored: StoredStory, threads: List<StoryThread>) {
        if (reader.markRead(stored, threads)) changed()
    }

    private fun changed() {
        draftsChanged()
        project.messageBus.syncPublisher(StoryReaderListener.TOPIC).readerChanged()
    }

    fun requestRefresh(stored: StoredStory, stepId: String?) = changeFile { service.requestRefresh(stored, stepId) }

    /** Takes back a comment that no agent fetched; [withdrawn] gets it on the EDT, to edit it. */
    fun withdraw(stored: StoredStory, thread: StoryThread, withdrawn: (StoryDraft) -> Unit = {}) = changeFile {
        val draft = service.withdraw(stored, thread.id)
        if (draft == null) notify("An agent already fetched this comment. It stays in the story until the agent replies.")
        else ApplicationManager.getApplication().invokeLater({ withdrawn(draft) }, ModalityState.nonModal(), project.disposed)
    }

    fun resolve(stored: StoredStory, thread: StoryThread) = changeFile { service.resolve(stored, thread.id) }

    private fun changeFile(action: () -> Unit) = background {
        try { action() } catch (e: java.io.IOException) { notify("Code Story could not change the story file: ${e.message}") }
    }

    fun copy(text: String) = CopyPasteManager.getInstance().setContents(StringSelection(text))

    private fun notify(message: String) = ApplicationManager.getApplication().invokeLater({
        NotificationGroupManager.getInstance().getNotificationGroup(StoryListPanel.NOTIFICATION_GROUP)
            .createNotification(message, NotificationType.INFORMATION).notify(project)
    }, ModalityState.nonModal(), project.disposed)
}
