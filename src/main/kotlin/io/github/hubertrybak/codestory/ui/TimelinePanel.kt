package io.github.hubertrybak.codestory.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.hubertrybak.codestory.AnchorTracker
import io.github.hubertrybak.codestory.CodeStoryService
import io.github.hubertrybak.codestory.StoryChangeInfo
import io.github.hubertrybak.codestory.StoryChanges
import io.github.hubertrybak.codestory.StoryListListener
import io.github.hubertrybak.codestory.StoryNavigationFiles
import io.github.hubertrybak.codestory.StoryReaderListener
import io.github.hubertrybak.codestory.StoryView
import io.github.hubertrybak.codestory.core.FileLookup
import io.github.hubertrybak.codestory.core.StepKind
import io.github.hubertrybak.codestory.core.StepStatus
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryAnchors
import io.github.hubertrybak.codestory.core.StoryFiles
import io.github.hubertrybak.codestory.core.StoryFolder
import io.github.hubertrybak.codestory.core.TextFiles
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.Rectangle
import java.util.concurrent.Future
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingUtilities

/**
 * The content of the Code Story Timeline tool window: every step of one story from top to bottom,
 * with the explanation of the agent and the code of the step. A click on a step plays it in the
 * timeline view, so the editor beside the timeline shows its code. The timeline follows the playing
 * story and its current step, revisions of the story, and edits of the code.
 */
class TimelinePanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val player = StoryPlayer.getInstance(project)
    private val readerComments = StoryComments(project)
    private val loader = AppExecutorUtil.createBoundedApplicationPoolExecutor("Code Story timeline", 1)
    private val codeRefresh = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private val storyModel = CollectionComboBoxModel<StoredStory>()
    val chooser = ComboBox(storyModel).apply {
        renderer = StoryChooserRenderer()
        getAccessibleContext().accessibleName = "Story"
        addActionListener { if (!choosing) (selectedItem as? StoredStory)?.takeIf { it.key != shown?.key }?.let { reload(it.key) } }
    }
    val header = Header()
    private val steps = JPanel(VerticalLayout(0)).apply { isOpaque = false }
    /** The comments about the whole story, after the last step. */
    val storyComments = CommentsPanel(readerComments).apply { isOpaque = false }
    private val footer = JPanel(VerticalLayout(8)).apply {
        isOpaque = false
        border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(12, 12, 16, 12))
        add(storyComments)
        add(storyComments.composer)
    }
    private val column = Column().apply {
        add(header)
        add(steps)
        add(footer)
    }
    private val scroll = ScrollPaneFactory.createScrollPane(column, true)
    private val empty = JBPanelWithEmptyText().withEmptyText("No stories yet").apply {
        emptyText.appendSecondaryText("Ask your agent: /code-story \"<request>\"", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
    }
    private val pages = JPanel(CardLayout()).apply {
        add(scroll, TIMELINE)
        add(empty, EMPTY)
    }

    /** One block for each step of [shown], in order. */
    var blocks: List<TimelineStep> = emptyList()
        private set

    /** The story that the timeline shows, with the current lines and status of its steps. */
    var shown: StoredStory? = null
        private set
    private var code: Map<Int, StepCode> = emptyMap()
    private var changes: StoryChanges? = null
    /** The paths of the files of the shown steps. Their edits refresh the code of the steps. */
    @Volatile private var watched: Set<String> = emptySet()
    private var loadVersion = 0L
    /** The story that the latest load shows, also while it reads, so that a later reload keeps it. */
    private var wantedKey: String? = null
    private var playbackVersion = player.playbackVersion
    /** The reader opened a story in the timeline: its current step comes into view when it shows. */
    private var revealPending = false
    private var commentPending = false
    private var choosing = false
    @Volatile private var disposed = false

    private val actions = object : TimelineActions {
        override fun navigate(index: Int, line: Int?, focus: Boolean) = this@TimelinePanel.navigate(index, line, focus)

        override fun showChangeDiff(index: Int) {
            latest(index)?.let { StoryChangeInfo.getInstance(project).showDiff(it, index) }
        }

        override fun previewProposal(index: Int) {
            latest(index)?.let { showProposalDiff(project, it, index) }
        }

        override fun askToApply(index: Int) {
            val stored = shown ?: return
            readerComments.post(stored, applyProposalDraft(stored, stored.story.steps.getOrNull(index)?.id ?: return))
        }
    }

    init {
        toolbar = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(2, 6, 2, 0)
            add(chooser, BorderLayout.CENTER)
            val group = DefaultActionGroup().apply {
                add(PlayWithCardAction())
                add(ActionManager.getInstance().getAction("CodeStory.PreviousStep"))
                add(ActionManager.getInstance().getAction("CodeStory.NextStep"))
                add(ActionManager.getInstance().getAction("CodeStory.StopStory"))
                addSeparator()
                add(RefreshAction())
            }
            add(ActionManager.getInstance().createActionToolbar("CodeStory.Timeline", group, true)
                .also { it.targetComponent = this@TimelinePanel }.component, BorderLayout.EAST)
        }
        setContent(pages)
        val connection = project.messageBus.connect(this)
        connection.subscribe(StoryListListener.TOPIC, object : StoryListListener {
            override fun storiesChanged(shownStoryKey: String?) = onEdt { reload() }
            override fun storyRelocated(previous: StoredStory, moved: StoredStory) = relocateStory(previous, moved)
        })
        connection.subscribe(StoryPlaybackListener.TOPIC, StoryPlaybackListener { updatePlayback() })
        connection.subscribe(StoryReaderListener.TOPIC, StoryReaderListener { onEdt { refreshComments() } })
        connection.subscribe(StoryTimelineListener.TOPIC, object : StoryTimelineListener {
            override fun storyRequested(key: String) {
                StoryTimeline.getInstance(project).takeRequestedKey()
                revealPending = true
                reload(key)
            }
            override fun commentRequested() {
                if (StoryTimeline.getInstance(project).takeCommentRequest()) startComment()
            }
        })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path in watched }) onEdt { scheduleCodeRefresh() }
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (file.path in watched) scheduleCodeRefresh()
            }
        }, this)
        // A comment request opened this tool window: the input gets the focus when the story shows.
        commentPending = StoryTimeline.getInstance(project).takeCommentRequest()
        reload(StoryTimeline.getInstance(project).takeRequestedKey() ?: player.currentStory?.key)
    }

    /**
     * Reads the stories in the background, then shows the story with [key]: by default the shown
     * or the playing story, or else the newest. The change information of a story with a base
     * follows, because it runs Git.
     */
    fun reload(key: String? = null) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (disposed) return
        val version = ++loadVersion
        val previous = shown
        val wanted = key ?: wantedKey ?: previous?.key ?: player.currentStory?.key
        wantedKey = wanted
        loader.execute {
            val stories = try {
                CodeStoryService.getInstance(project).stories()
            } catch (e: Exception) {
                if (e is ControlFlowException) throw e
                LOG.warn("Code Story cannot read the stories for the timeline", e)
                return@execute
            }
            // Share and Unshare move the shown story to another file.
            val chosen = stories.firstOrNull { it.key == wanted }
                ?: previous?.let { shown -> stories.firstOrNull { sameStory(it, shown) } }
                ?: stories.firstOrNull()
            val code = chosen?.let { readCode(it) }.orEmpty()
            onEdt { if (version == loadVersion) show(stories, chosen, code) }
            val changes = chosen?.let(::changesOf)
            onEdt { if (version == loadVersion && shown?.key == chosen?.key) showChanges(changes) }
        }
    }

    /** Completes when the stories that were requested before are shown, with their change information. */
    @TestOnly
    internal fun loaded(): Future<*> = loader.submit {}

    private fun show(stories: List<StoredStory>, stored: StoredStory?, code: Map<Int, StepCode>) {
        // The wanted story can be gone; the newest story shows instead.
        wantedKey = stored?.key
        choosing = true
        try {
            storyModel.replaceAll(stories)
            storyModel.selectedItem = stored
        } finally {
            choosing = false
        }
        chooser.isEnabled = stories.isNotEmpty()
        showStory(stored, code)
    }

    /** Updates the blocks of the steps in place, so comment input and the scroll position stay. */
    private fun showStory(stored: StoredStory?, code: Map<Int, StepCode>) {
        val layout = pages.layout as CardLayout
        if (stored == null) {
            blocks.forEach(Disposer::dispose)
            blocks = emptyList()
            steps.removeAll()
            shown = null
            watched = emptySet()
            layout.show(pages, EMPTY)
            return
        }
        val same = shown?.let { sameStory(it, stored) } == true
        if (!same || changes?.baseRef != stored.story.baseRef) changes = null
        shown = stored
        this.code = code
        header.show(stored)
        val reusable = if (same) blocks.filter { it.stepId != null }.associateBy { it.stepId }.toMutableMap() else mutableMapOf()
        val next = stored.story.steps.map { step ->
            step.id?.let(reusable::remove) ?: TimelineStep(project, readerComments, actions).also { Disposer.register(this, it) }
        }
        (blocks - next.toSet()).forEach(Disposer::dispose)
        if (next != blocks) {
            steps.removeAll()
            next.forEach(steps::add)
            blocks = next
        }
        updateBlocks()
        storyComments.show(stored, null)
        watched = code.values.mapNotNull { it.file?.path }.toSet()
        layout.show(pages, TIMELINE)
        column.revalidate()
        column.repaint()
        val reveal = !same || revealPending
        revealPending = false
        updatePlayback(scroll = reveal)
        if (commentPending) {
            commentPending = false
            startComment()
        }
    }

    private fun updateBlocks() {
        val stored = shown ?: return
        val current = currentIndex()
        blocks.forEachIndexed { index, block ->
            val step = stored.story.steps[index]
            block.update(TimelineItem(stored, index, code[index], changes?.step(step.id), changes?.commit != null && step.location != null,
                first = index == 0, last = index == blocks.lastIndex), current = index == current)
        }
    }

    private fun showChanges(changes: StoryChanges?) {
        this.changes = changes
        updateBlocks()
    }

    /** The index of the playing step, when the timeline shows the playing story. */
    private fun currentIndex(): Int? {
        val shown = shown ?: return null
        return player.currentStory?.takeIf { sameStory(it, shown) }?.let { player.stepIndex }
    }

    /** A new playback shows its story here; the current step gets the accent and comes into view. */
    private fun updatePlayback(scroll: Boolean = false) {
        if (disposed) return
        val started = player.playbackVersion != playbackVersion
        playbackVersion = player.playbackVersion
        val playing = player.currentStory
        val shown = shown
        if (started && playing != null && (shown == null || !sameStory(playing, shown))) {
            reload(playing.key)
            return
        }
        val current = currentIndex()
        blocks.forEachIndexed { index, block -> block.setCurrent(index == current) }
        if ((started || scroll) && current != null) scrollTo(current)
    }

    /** Brings the top of the block of step [index] into view, unless the block is visible already. */
    private fun scrollTo(index: Int) = onEdt {
        val block = blocks.getOrNull(index) ?: return@onEdt
        if (block.parent == null) return@onEdt
        scroll.validate()
        val bounds = SwingUtilities.convertRectangle(block.parent, block.bounds, column)
        val visible = column.visibleRect
        val top = Rectangle(bounds.x, bounds.y, bounds.width, minOf(bounds.height, visible.height))
        if (!visible.contains(top)) column.scrollRectToVisible(top)
    }

    /** Plays step [index] in the timeline view, then puts the caret at [line]. */
    private fun navigate(index: Int, line: Int?, focus: Boolean) {
        val shown = shown ?: return
        val stepId = shown.story.steps.getOrNull(index)?.id
        // The playing story has the lines that its live markers followed.
        val stored = player.currentStory?.takeIf { sameStory(it, shown) } ?: shown
        val target = stored.story.steps.indexOfFirst { it.id == stepId }.takeIf { it >= 0 } ?: index.coerceAtMost(stored.story.steps.lastIndex)
        player.play(stored, target, StoryView.TIMELINE) { presentation ->
            val editor = presentation?.editor ?: return@play
            if (line != null && line in 1..editor.document.lineCount) {
                editor.caretModel.moveToLogicalPosition(LogicalPosition(line - 1, 0))
                editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            }
            if (focus) IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
        }
    }

    /** The newest version of the shown story for step [index]: a playing story has the lines that its live markers followed. */
    private fun latest(index: Int): StoredStory? {
        val shown = shown ?: return null
        val stepId = shown.story.steps.getOrNull(index)?.id
        return player.currentStory?.takeIf { sameStory(it, shown) && it.story.steps.getOrNull(index)?.id == stepId } ?: shown
    }

    /** Moves the keyboard focus to the comment input of the current step, or of the story. */
    private fun startComment() {
        val playing = player.currentStory
        val shown = shown
        if (playing != null && (shown == null || !sameStory(playing, shown))) {
            commentPending = true
            reload(playing.key)
            return
        }
        val block = blocks.firstOrNull { it.isCurrent }
        if (block != null) {
            scrollTo(block.index)
            block.startComment()
        } else {
            onEdt { column.scrollRectToVisible(SwingUtilities.convertRectangle(footer.parent, footer.bounds, column)) }
            storyComments.startComment()
        }
    }

    private fun refreshComments() {
        val stored = shown ?: return
        blocks.forEach { it.refreshComments(stored) }
        storyComments.show(stored, null)
    }

    /** Share and Unshare move the story to another file; its unsent input moves with it. */
    private fun relocateStory(previous: StoredStory, moved: StoredStory) {
        blocks.forEach { it.comments.relocated(previous, moved) }
        storyComments.relocated(previous, moved)
        if (shown?.key == previous.key) reload(moved.key)
    }

    /** Edits change the code of the steps; the timeline shows it after a short pause in typing. */
    private fun scheduleCodeRefresh() {
        if (disposed || shown == null) return
        codeRefresh.cancelAllRequests()
        codeRefresh.addRequest(::refreshCode, CODE_REFRESH_DELAY_MS)
    }

    private fun refreshCode() {
        val stored = shown ?: return
        val version = loadVersion
        loader.execute {
            val live = AnchorTracker.getInstance(project).snapshot()
            val files = CodeStoryService.getInstance(project).ideFiles(stored.root, live)
            val resolved = stored.resolved(StoryAnchors.resolve(stored.story, files, live?.takeIf { it.storyKey == stored.key }?.ranges.orEmpty()))
            val code = readCode(resolved, files)
            onEdt { if (version == loadVersion && shown === stored) showStory(resolved, code) }
        }
    }

    /**
     * The code of each step as the IDE shows it now, including unsaved edits. A lost step shows the
     * code that it was written for. Reads files, so call it in the background.
     */
    private fun readCode(stored: StoredStory, files: StoryFiles = CodeStoryService.getInstance(project).ideFiles(stored.root)): Map<Int, StepCode> {
        val texts = HashMap<String, List<String>?>()
        val virtualFiles = HashMap<String, VirtualFile?>()
        val result = HashMap<Int, StepCode>()
        stored.story.steps.forEachIndexed { index, step ->
            val location = step.location ?: return@forEachIndexed
            val lines = texts.getOrPut(location.file) { (files.lookup(location.file) as? FileLookup.Found)?.let { TextFiles.lines(it.text) } }
            val current = lines?.takeIf { location.endLine <= it.size }?.subList(location.startLine - 1, location.endLine)
            val saved = location.rangeText?.split("\n")
            val shownCode = (if (stored.status(index) == StepStatus.LOST) saved ?: current else current ?: saved) ?: return@forEachIndexed
            val file = virtualFiles.getOrPut(location.file) { StoryNavigationFiles.find(stored.root, location.file) }
            result[index] = StepCode(shownCode, location.startLine, file, location.file.substringAfterLast('/'))
        }
        return result
    }

    /** A Git problem leaves the story without change information, and the timeline still shows it. */
    private fun changesOf(stored: StoredStory): StoryChanges? = try {
        StoryChangeInfo.getInstance(project).changes(stored)
    } catch (e: Exception) {
        if (e is ControlFlowException) throw e
        LOG.warn("Code Story cannot read the changes of ${stored.id}", e)
        null
    }

    private fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeLater(
        action, ModalityState.any(), Condition<Any?> { disposed || project.isDisposed },
    )

    override fun dispose() {
        disposed = true
        loader.shutdownNow()
    }

    /** The title, the summary and the metadata of the shown story. Story text shows as plain text or through [StoryTextPane]. */
    inner class Header : JPanel(VerticalLayout(6)) {
        val title = object : PlainText(bold = true, keepCaret = true) {
            // The theme sets the label font again.
            override fun updateUI() {
                super.updateUI()
                font = JBFont.h3().asBold()
            }
        }.apply { font = JBFont.h3().asBold() }
        val meta = muted()
        val summary = StoryTextPane { link -> shown?.let { openStoryLink(project, it.root, link) } }
        val request = muted()
        val author = muted()

        init {
            isOpaque = false
            border = JBUI.Borders.empty(12, 12, 12, TimelineStep.RIGHT_MARGIN)
            add(title)
            add(meta)
            add(summary)
            add(request)
            add(author)
        }

        fun show(stored: StoredStory) {
            val story = stored.story
            title.text = story.title
            val count = story.steps.size
            val proposals = story.steps.count { it.kind == StepKind.PROPOSAL }
            val context = story.steps.count { it.kind == StepKind.CONTEXT }
            val kinds = listOfNotNull(
                proposals.takeIf { it > 0 }?.let { if (it == 1) "1 proposal" else "$it proposals" },
                context.takeIf { it > 0 }?.let { if (it == 1) "1 context step" else "$it context steps" },
            )
            val branch = story.git.branch ?: if (story.git.commit != null) "detached HEAD" else null
            meta.text = listOfNotNull(
                storyTypeLabel(story.type),
                "$count ${if (count == 1) "step" else "steps"}" + if (kinds.isEmpty()) "" else " (${kinds.joinToString(", ")})",
                stored.date,
                branch,
                "shared".takeIf { stored.folder == StoryFolder.SHARED_DIR },
            ).joinToString(" · ")
            summary.markdown = story.summary
            request.text = "Request: ${story.request}"
            author.text = "Written by ${story.author.agent} (${story.author.model})"
        }

        private fun muted() = PlainText(keepCaret = true).apply { foreground = UIUtil.getContextHelpForeground() }
    }

    /** Text wraps at the width of the tool window; the column scrolls. */
    private class Column : JPanel(VerticalLayout(0)), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    private inner class PlayWithCardAction : DumbAwareAction("Play with Step Card", "Follow the story with the step card beside the code", AllIcons.Actions.Execute) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = shown != null }
        override fun actionPerformed(e: AnActionEvent) {
            val shown = shown ?: return
            if (player.currentStory?.let { sameStory(it, shown) } == true) player.showIn(StoryView.CARD)
            else player.play(shown, null, StoryView.CARD)
        }
    }

    private inner class RefreshAction : DumbAwareAction("Refresh", "Read the stories again", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = reload()
    }

    companion object {
        private const val TIMELINE = "timeline"
        private const val EMPTY = "empty"
        private const val CODE_REFRESH_DELAY_MS = 300
        private val LOG = logger<TimelinePanel>()

        /** The same story file can move between Local and Shared; its root and ID stay. */
        fun sameStory(a: StoredStory, b: StoredStory) = a.root == b.root && a.id == b.id
    }
}

/** Story titles come from story files, so they show as plain text, never as HTML. */
private class StoryChooserRenderer : ColoredListCellRenderer<StoredStory>() {
    override fun customizeCellRenderer(list: JList<out StoredStory>, value: StoredStory?, index: Int, selected: Boolean, hasFocus: Boolean) {
        if (value == null) {
            append("No story", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            return
        }
        icon = storyTypeIcon(value.story.type)
        append(value.story.title)
        append("  ${value.date}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (value.folder == StoryFolder.SHARED_DIR) append("  shared", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}
