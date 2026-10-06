package io.github.hubertrybak.codestory.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.messages.Topic
import io.github.hubertrybak.codestory.CodeStoryService
import io.github.hubertrybak.codestory.core.StoredStory

@Service(Service.Level.PROJECT)
@State(name = "CodeStoryReadingPosition", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class StoryReadingPosition : PersistentStateComponent<StoryReadingPosition.Positions> {
    class Positions {
        var steps: MutableMap<String, Int> = mutableMapOf()
    }

    private var positions = Positions()
    override fun getState() = positions
    override fun loadState(state: Positions) { positions = state }
}

fun interface StoryPlaybackListener {
    fun playbackChanged()

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic(StoryPlaybackListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/** All reader controls call this one project player, on the EDT. */
@Service(Service.Level.PROJECT)
class StoryPlayer(private val project: Project) : Disposable {
    var currentStory: StoredStory? = null
        private set
    var stepIndex = 0
        private set
    /** Kept through text-only steps so their playback does not change the editor. */
    var presentation: StepPresentation? = null
        private set
    internal var playbackVersion = 0
        private set
    private val visited = mutableMapOf<String, MutableSet<Int>>()

    val isPlaying get() = currentStory != null
    val isLastStep get() = currentStory?.let { stepIndex == it.story.steps.lastIndex } == true

    fun wasVisited(storyId: String, index: Int) = index in visited[storyId].orEmpty()

    /** Without an explicit index, Play resumes the position saved in this IDE's workspace. */
    fun play(stored: StoredStory, index: Int? = null) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val target = (index ?: project.service<StoryReadingPosition>().state.steps[stored.id] ?: 0)
            .coerceIn(stored.story.steps.indices)
        val step = stored.story.steps[target]
        val location = step.location
        if (location != null) {
            clearPresentation()
            val editor = StepNavigator.getInstance(project).open(stored.root, location)
            if (editor == null) {
                currentStory = null
                changed()
                NotificationGroupManager.getInstance().getNotificationGroup(StoryListPanel.NOTIFICATION_GROUP)
                    .createNotification("Code Story cannot open ${location.file}.", NotificationType.WARNING).notify(project)
                return
            }
            presentation = StepPresentation(project, editor, stored, target, this).also { Disposer.register(this, it) }
        } else {
            presentation?.dismissCard()
        }
        currentStory = stored
        stepIndex = target
        project.service<StoryReadingPosition>().state.steps[stored.id] = target
        visited.getOrPut(stored.id) { mutableSetOf() }.add(target)
        playbackVersion++
        changed()
        if (location != null) presentation!!.showCard()
        else ToolWindowManager.getInstance(project).getToolWindow(CodeStoryService.TOOL_WINDOW_ID)?.show()
    }

    fun restart(stored: StoredStory) = play(stored, 0)
    fun previous() { currentStory?.takeIf { stepIndex > 0 }?.let { play(it, stepIndex - 1) } }
    fun next() { currentStory?.let { if (isLastStep) stop() else play(it, stepIndex + 1) } }

    fun stop() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        clearPresentation()
        currentStory = null
        playbackVersion++
        changed()
    }

    internal fun changed() = project.messageBus.syncPublisher(StoryPlaybackListener.TOPIC).playbackChanged()

    private fun clearPresentation() {
        presentation?.let { Disposer.dispose(it) }
        presentation = null
        StepNavigator.getInstance(project).clear()
    }

    override fun dispose() {
        clearPresentation()
        currentStory = null
    }

    companion object {
        fun getInstance(project: Project): StoryPlayer = project.service()
    }
}
