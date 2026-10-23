package io.github.hubertrybak.codestory.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.messages.Topic
import io.github.hubertrybak.codestory.CodeStoryService
import io.github.hubertrybak.codestory.CodeStorySettings
import io.github.hubertrybak.codestory.StoryView
import io.github.hubertrybak.codestory.core.StoredStory

/** Requests for the Code Story Timeline tool window. */
interface StoryTimelineListener {
    /** The reader opened the story with [key] in the timeline. */
    fun storyRequested(key: String)

    /** The reader wants to comment on the current step in the timeline. */
    fun commentRequested()

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic(StoryTimelineListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/**
 * Opens stories in the timeline: every step of a story from top to bottom, with its code, beside the
 * editor. The editor follows the step that the reader selects in the timeline, without a step card.
 */
@Service(Service.Level.PROJECT)
class StoryTimeline(private val project: Project) {
    /** The story that the reader opened in the timeline, until the timeline shows it. */
    private var requestedKey: String? = null

    /** A comment request that the timeline did not take yet, because its tool window was not open. */
    private var commentRequest = false

    /**
     * Shows [stored] in the timeline. A playing story continues there at its step, and new stories
     * open there too. A timeline that opens now takes the request; an open one gets it as an event.
     */
    fun open(stored: StoredStory) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        requestedKey = stored.key
        val player = StoryPlayer.getInstance(project)
        if (player.currentStory?.key == stored.key) player.showIn(StoryView.TIMELINE)
        else CodeStorySettings.getInstance().state.storyView = StoryView.TIMELINE
        toolWindow()?.show()
        project.messageBus.syncPublisher(StoryTimelineListener.TOPIC).storyRequested(stored.key)
    }

    /** Shows the timeline with its current story, without changing playback. */
    fun show() {
        toolWindow()?.show()
    }

    /** Moves the keyboard focus to the comment input of the current step in the timeline. */
    fun startComment() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        commentRequest = true
        toolWindow()?.show()
        project.messageBus.syncPublisher(StoryTimelineListener.TOPIC).commentRequested()
    }

    /** Whether the reader asked to comment since the last call. */
    fun takeCommentRequest(): Boolean = commentRequest.also { commentRequest = false }

    /** The story that the reader opened since the last call, if any. */
    fun takeRequestedKey(): String? = requestedKey.also { requestedKey = null }

    private fun toolWindow(): ToolWindow? = ToolWindowManager.getInstance(project).getToolWindow(CodeStoryService.TIMELINE_TOOL_WINDOW_ID)

    companion object {
        fun getInstance(project: Project): StoryTimeline = project.service()
    }
}
