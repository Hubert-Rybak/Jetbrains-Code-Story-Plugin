package io.github.hubertrybak.codestory.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.messages.Topic
import io.github.hubertrybak.codestory.AnchorTracker
import io.github.hubertrybak.codestory.CodeStoryService
import io.github.hubertrybak.codestory.CodeStorySettings
import io.github.hubertrybak.codestory.LiveSnapshot
import io.github.hubertrybak.codestory.StoryReaderState
import io.github.hubertrybak.codestory.StoryView
import io.github.hubertrybak.codestory.core.StepStatus
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryAnchors
import io.github.hubertrybak.codestory.core.StoryFiles
import io.github.hubertrybak.codestory.core.StoryThreads
import java.nio.file.Path
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

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
 * Reader controls update the requested position on the EDT. Source reads and anchor searches
 * run in the background; only the latest request may install its resolved story and editor UI.
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
    internal var isResolving = false
        private set
    @Volatile private var requestVersion = 0L
    @Volatile private var documentVersion = 0L
    private var pending: Future<*>? = null
    private var disposed = false
    private val runningChecks = AtomicInteger()
    internal val isCheckingAnchors get() = runningChecks.get() != 0

    /** Kept injectable so tests can pause real file lookup at the background boundary. */
    internal var filesForPlayback: (Path, LiveSnapshot?) -> StoryFiles = { root, live ->
        CodeStoryService.getInstance(project).ideFiles(root, live)
    }

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) { documentVersion++ }
        }, this)
    }

    val isPlaying get() = currentStory != null
    val isLastStep get() = currentStory?.let { stepIndex == it.story.steps.lastIndex } == true

    /**
     * Where the reader follows the steps: the step card beside the code, or the timeline. In the
     * timeline, the editor shows the code of the step with its highlight and gutter badges, and no card.
     */
    val view: StoryView get() = CodeStorySettings.getInstance().state.storyView

    fun wasVisited(stored: StoredStory, index: Int) = index in visited[stored.key].orEmpty()

    /** Without an explicit index, Play resumes the position saved in this IDE's workspace. */
    fun play(stored: StoredStory, index: Int? = null) = start(stored, index, null)

    /**
     * Plays in [view], which new stories then also use. [afterPresentation] gets the presentation of
     * the step in its editor when the step shows, or `null` when the step has no code in an editor.
     */
    fun play(stored: StoredStory, index: Int?, view: StoryView, afterPresentation: (StepPresentation?) -> Unit = {}) {
        CodeStorySettings.getInstance().state.storyView = view
        start(stored, index) {
            afterPresentation(presentation?.takeIf { it.index == stepIndex && it.card.currentStory.key == currentStory?.key && !it.disposed })
        }
    }

    /** Continues the playing story in [view] at the same step. New stories also open in [view]. */
    fun showIn(view: StoryView) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        CodeStorySettings.getInstance().state.storyView = view
        val current = currentStory
        when {
            current == null -> changed()
            view == StoryView.CARD -> play(current, stepIndex)
            else -> {
                presentation?.dismissCard()
                showExplanation()
                changed()
            }
        }
    }

    private fun start(stored: StoredStory, index: Int?, afterPresentation: (() -> Unit)?) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (disposed || project.isDisposed) return
        cancelPending()
        if (currentStory?.key != stored.key) syncAnchors()
        val positions = project.service<StoryReadingPosition>().state.steps
        val canMigrate = project.service<StoryReaderState>().canMigrateLegacy(stored)
        // Older workspace versions identified a story only by its root and ID.
        if (canMigrate && stored.key !in positions) positions.remove(stored.legacyKey)?.let { positions[stored.key] = it }
        // Before repository grouping, positions belonged only to the project's default root.
        if (canMigrate && stored.key !in positions && stored.root == CodeStoryService.getInstance(project).storyFolder().root) {
            positions.remove(stored.id)?.let { positions[stored.key] = it }
        }
        val target = (index ?: positions[stored.key] ?: 0)
            .coerceIn(stored.story.steps.indices)
        if (stored.story.steps[target].location != null) clearPresentation()
        currentStory = stored
        stepIndex = target
        positions[stored.key] = target
        playbackVersion++
        isResolving = true
        changed()
        resolve(stored, target, requestVersion, afterPresentation)
    }

    private fun resolve(stored: StoredStory, target: Int, version: Long, afterPresentation: (() -> Unit)?) {
        val application = ApplicationManager.getApplication()
        val filesFactory = filesForPlayback
        pending = application.executeOnPooledThread {
            runningChecks.incrementAndGet()
            try {
                // Refresh without a read lock: VFS refresh can need the EDT to deliver events.
                stored.story.steps[target].location?.let {
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(stored.root.resolve(it.file))
                }
                if (version != requestVersion || project.isDisposed) return@executeOnPooledThread
                val input = application.runReadAction(Computable {
                    val live = AnchorTracker.getInstance(project).snapshot()
                    Triple(filesFactory(stored.root, live), live, documentVersion)
                })
                val ranges = input.second?.takeIf { it.storyKey == stored.key }?.ranges.orEmpty()
                val resolved = stored.resolved(StoryAnchors.resolve(stored.story, input.first, ranges))
                if (version != requestVersion || project.isDisposed) return@executeOnPooledThread
                val documents = AnchorTracker.getInstance(project).prepare(resolved)
                application.invokeLater({
                    if (disposed || version != requestVersion) return@invokeLater
                    val current = currentStory ?: return@invokeLater
                    if (input.third != documentVersion || current.key != stored.key) {
                        // Edits invalidate captured text; relocation changes the live-marker key.
                        resolve(current, target, version, afterPresentation)
                        return@invokeLater
                    }
                    // Thread-only updates and Share/Unshare can arrive while the source is read.
                    val latest = StoredStory(current.id, current.root, current.file,
                        StoryThreads.carryOver(current.story, resolved.story), current.modified, resolved.statuses, current.isNew)
                    CodeStoryService.getInstance(project).anchorsChecked(current, latest)
                    present(latest, target, documents)
                    pending = null
                    isResolving = false
                    afterPresentation?.invoke()
                    changed()
                }, ModalityState.nonModal(), project.disposed)
            } catch (_: ProcessCanceledException) {
                // A superseding request or project disposal owns the next state.
            } catch (error: Exception) {
                if (Thread.currentThread().isInterrupted || version != requestVersion) return@executeOnPooledThread
                LOG.warn("Code Story cannot resolve ${stored.id}", error)
                application.invokeLater({
                    if (disposed || version != requestVersion) return@invokeLater
                    pending = null
                    isResolving = false
                    NotificationGroupManager.getInstance().getNotificationGroup(StoryListPanel.NOTIFICATION_GROUP)
                        .createNotification("Code Story could not read the story's source files.", NotificationType.WARNING).notify(project)
                    changed()
                }, ModalityState.nonModal(), project.disposed)
            } finally {
                runningChecks.decrementAndGet()
            }
        }
    }

    private fun present(resolved: StoredStory, target: Int, documents: Map<String, AnchorTracker.SourceDocument>) {
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
        AnchorTracker.getInstance(project).install(resolved, documents)
        CodeStoryService.getInstance(project).opened(resolved)
        currentStory = resolved.withNewMarker(false)
        stepIndex = target
        visited.getOrPut(resolved.key) { mutableSetOf() }.add(target)
        when {
            editor == null -> showExplanation()
            view == StoryView.CARD -> presentation!!.showCard()
            else -> {
                // The timeline explains the step, so the editor shows only its code.
                presentation!!.dismissCard()
                showExplanation()
            }
        }
    }

    /**
     * Shows the explanation of a step that has no card: the details pane of the Code Story tool
     * window, or the timeline. A visible Code Story tool window also explains the step in the
     * timeline view, so it stays in view.
     */
    private fun showExplanation() {
        val manager = ToolWindowManager.getInstance(project)
        val details = manager.getToolWindow(CodeStoryService.TOOL_WINDOW_ID)
        if (view == StoryView.CARD) {
            details?.show()
            return
        }
        val timeline = manager.getToolWindow(CodeStoryService.TIMELINE_TOOL_WINDOW_ID)
        if (timeline?.isVisible != true && details?.isVisible != true) timeline?.show()
    }

    fun restart(stored: StoredStory) = play(stored, 0)
    /** Replace all presentation objects, even when the new current step is text-only. */
    fun reload(stored: StoredStory) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val index = stepIndex.coerceAtMost(stored.story.steps.lastIndex)
        val comments = presentation?.card?.comments
        clearPresentation()
        start(stored, index) { comments?.let { presentation?.card?.comments?.restoreInput(it) } }
    }
    /** Sharing changes the file location without restarting playback or moving the editor. */
    fun relocated(previous: StoredStory, stored: StoredStory) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val positions = project.service<StoryReadingPosition>().state.steps
        positions.remove(previous.key)?.let { positions[stored.key] = it }
        visited.remove(previous.key)?.let { visited[stored.key] = it }
        val current = currentStory?.takeIf { it.key == previous.key } ?: return
        currentStory = StoredStory(current.id, current.root, stored.file, current.story, stored.modified, current.statuses, current.isNew)
        AnchorTracker.getInstance(project).relocated(previous, checkNotNull(currentStory))
        presentation?.relocated(previous, checkNotNull(currentStory))
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
        currentStory = StoredStory(current.id, current.root, stored.file, story, stored.modified, current.statuses, current.isNew)
        presentation?.takeIf { current.story.steps[stepIndex].location != null }?.card?.refreshComments(checkNotNull(currentStory))
        changed()
    }

    fun commentOnCurrentStep() {
        val current = currentStory ?: return
        if (view == StoryView.TIMELINE) StoryTimeline.getInstance(project).startComment()
        else if (current.story.steps[stepIndex].location != null && presentation != null) presentation!!.startComment()
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
        cancelPending()
        syncAnchors()
        clearPresentation()
        currentStory = null
        playbackVersion++
        changed()
    }

    internal fun changed() = project.messageBus.syncPublisher(StoryPlaybackListener.TOPIC).playbackChanged()

    /** Keeps what the live markers of the current story followed, then removes them. */
    private fun syncAnchors() {
        if (project.isDisposed) return
        val tracker = AnchorTracker.getInstance(project)
        val live = tracker.snapshot()
        tracker.release()
        val stored = currentStory ?: return
        // No installed markers means this story never finished opening, or has no code to save.
        if (live?.storyKey != stored.key) return
        val filesFactory = filesForPlayback
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            try {
                val service = CodeStoryService.getInstance(project)
                val resolved = stored.resolved(StoryAnchors.resolve(stored.story, filesFactory(stored.root, live), live.ranges))
                if (!project.isDisposed) service.anchorsChecked(stored, resolved)
            } catch (_: ProcessCanceledException) {
                // The project is closing.
            } catch (error: Exception) {
                LOG.warn("Code Story cannot save the followed anchors of ${stored.id}", error)
            }
        }
    }

    private fun cancelPending() {
        requestVersion++
        pending?.cancel(true)
        pending = null
        isResolving = false
    }

    private fun clearPresentation() {
        presentation?.let { Disposer.dispose(it) }
        presentation = null
        StepNavigator.getInstance(project).clear()
    }

    override fun dispose() {
        disposed = true
        cancelPending()
        clearPresentation()
        currentStory = null
    }

    companion object {
        private val LOG = Logger.getInstance(StoryPlayer::class.java)
        fun getInstance(project: Project): StoryPlayer = project.service()
    }
}
