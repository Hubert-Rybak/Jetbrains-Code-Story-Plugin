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
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.messages.Topic
import io.github.hubertrybak.codestory.AnchorTracker
import io.github.hubertrybak.codestory.CodeStoryService
import io.github.hubertrybak.codestory.core.StepStatus
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryThreads

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

fun interface StoryCommentListener {
    fun commentRequested()
    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic(StoryCommentListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/**
 * All reader controls call this one project player, on the EDT. Each play checks the anchors of
 * the story, so [currentStory] has the current lines and step statuses.
 */
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

    fun wasVisited(stored: StoredStory, index: Int) = index in visited[stored.key].orEmpty()

    /** Without an explicit index, Play resumes the position saved in this IDE's workspace. */
    fun play(stored: StoredStory, index: Int? = null) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (currentStory?.key != stored.key) syncAnchors()
        val positions = project.service<StoryReadingPosition>().state.steps
        // Before repository grouping, positions belonged only to the project's default root.
        if (stored.key !in positions && stored.root == CodeStoryService.getInstance(project).storyFolder().root) {
            positions.remove(stored.id)?.let { positions[stored.key] = it }
        }
        val target = (index ?: positions[stored.key] ?: 0)
            .coerceIn(stored.story.steps.indices)
        // The anchor check reads the file as the IDE has it, so refresh it from disk first.
        stored.story.steps[target].location?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(stored.root.resolve(it.file)) }
        val resolved = checkAnchors(stored)
        val location = resolved.story.steps[target].location
        // A lost step plays at its stored lines. When its file cannot open, it plays like a text-only step.
        val editor = location?.let {
            clearPresentation()
            StepNavigator.getInstance(project).open(resolved.root, it)
        }
        if (location != null && editor == null && resolved.status(target) != StepStatus.LOST) {
            NotificationGroupManager.getInstance().getNotificationGroup(StoryListPanel.NOTIFICATION_GROUP)
                .createNotification("Code Story cannot open ${location.file}.", NotificationType.WARNING).notify(project)
        }
        if (editor != null) {
            presentation = StepPresentation(project, editor, resolved, target, this).also { Disposer.register(this, it) }
        } else {
            presentation?.dismissCard()
        }
        CodeStoryService.getInstance(project).opened(resolved)
        currentStory = resolved.withNewMarker(false)
        stepIndex = target
        positions[stored.key] = target
        visited.getOrPut(stored.key) { mutableSetOf() }.add(target)
        playbackVersion++
        changed()
        if (editor != null) presentation!!.showCard()
        else ToolWindowManager.getInstance(project).getToolWindow(CodeStoryService.TOOL_WINDOW_ID)?.show()
    }

    fun restart(stored: StoredStory) = play(stored, 0)
    /** Replace all presentation objects, even when the new current step is text-only. */
    fun reload(stored: StoredStory) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val index = stepIndex.coerceAtMost(stored.story.steps.lastIndex)
        val comments = presentation?.card?.comments
        clearPresentation()
        play(stored, index)
        comments?.let { presentation?.card?.comments?.restoreInput(it) }
    }
    /** Sharing changes the file location without restarting playback or moving the editor. */
    fun relocated(stored: StoredStory) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val current = currentStory?.takeIf { it.key == stored.key } ?: return
        currentStory = StoredStory(current.id, current.root, stored.file, current.story, stored.modified, current.statuses)
        changed()
    }

    /**
     * The comment threads of the playing story changed in its file. The story reloads its threads
     * and stays at the same step, with its lines, card and highlight.
     */
    fun threadsChanged(stored: StoredStory) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val current = currentStory?.takeIf { it.key == stored.key } ?: return
        // The playing story keeps the lines that its live markers followed.
        val story = StoryThreads.carryOver(stored.story, current.story)
        currentStory = StoredStory(current.id, current.root, stored.file, story, stored.modified, current.statuses)
        presentation?.takeIf { current.story.steps[stepIndex].location != null }?.card?.refreshComments(checkNotNull(currentStory))
        changed()
    }

    fun commentOnCurrentStep() {
        val current = currentStory ?: return
        if (current.story.steps[stepIndex].location != null && presentation != null) presentation!!.startComment()
        else {
            ToolWindowManager.getInstance(project).getToolWindow(CodeStoryService.TOOL_WINDOW_ID)?.show {
                project.messageBus.syncPublisher(StoryCommentListener.TOPIC).commentRequested()
            }
        }
    }

    fun previous() { currentStory?.takeIf { stepIndex > 0 }?.let { play(it, stepIndex - 1) } }
    fun next() { currentStory?.let { if (isLastStep) stop() else play(it, stepIndex + 1) } }

    fun stop() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        syncAnchors()
        clearPresentation()
        currentStory = null
        playbackVersion++
        changed()
    }

    internal fun changed() = project.messageBus.syncPublisher(StoryPlaybackListener.TOPIC).playbackChanged()

    /** Finds the code of every step, following live markers, and saves moved anchors of a local story. */
    private fun checkAnchors(stored: StoredStory): StoredStory {
        val service = CodeStoryService.getInstance(project)
        val resolved = AnchorTracker.getInstance(project).track(stored, service.ideFiles(stored.root))
        service.anchorsChecked(stored, resolved)
        return resolved
    }

    /** Keeps what the live markers of the current story followed, then removes them. */
    private fun syncAnchors() {
        if (project.isDisposed) return
        currentStory?.let { checkAnchors(it) }
        AnchorTracker.getInstance(project).release()
    }

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
