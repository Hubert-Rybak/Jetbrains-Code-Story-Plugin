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
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Condition
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.ClickListener
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.tree.TreeUtil
import io.github.hubertrybak.codestory.CodeStoryService
import io.github.hubertrybak.codestory.StoryChangeInfo
import io.github.hubertrybak.codestory.StoryChanges
import io.github.hubertrybak.codestory.StoryListListener
import io.github.hubertrybak.codestory.StoryGitMetadata
import io.github.hubertrybak.codestory.core.Location
import io.github.hubertrybak.codestory.core.StepChange
import io.github.hubertrybak.codestory.core.StepStatus
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryGit
import io.github.hubertrybak.codestory.core.StoryType
import org.jetbrains.annotations.TestOnly
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.BorderLayout
import java.nio.file.Path
import java.util.concurrent.Future
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** A story row in the tree. [changes] is `null` for a story without a base, or without the Git plugin. */
class StoryItem(val stored: StoredStory, val changes: StoryChanges? = null)

/** A step row in the tree; [index] is 0-based. */
class StepItem(val stored: StoredStory, val index: Int, val changes: StoryChanges? = null) {
    val step get() = stored.story.steps[index]

    /** What the step added or modified since the story's base. */
    val change: StepChange? get() = changes?.step(step.id)

    /** A step with code can show its diff when Git knows the story's base. */
    val canShowDiff: Boolean get() = changes?.commit != null && step.location != null
}

/** The content of the Code Story tool window: the stories and their steps. A click on a step opens its code. */
class StoryListPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val rootNode = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(rootNode)
    val tree: Tree = Tree(treeModel)
    val cardContainer = JPanel(BorderLayout())
    private val player = StoryPlayer.getInstance(project)
    val details = StoryDetailsPanel(::showDiff)
    /** The context menu of stories and steps. */
    val contextActions = DefaultActionGroup(RestartAction(), ShowDiffAction())
    private var currentGit: Map<Path, StoryGit> = emptyMap()
    private var changes: Map<String, StoryChanges> = emptyMap()
    private var playbackVersion = -1
    private val loader = AppExecutorUtil.createBoundedApplicationPoolExecutor("Code Story story list", 1)

    @Volatile
    private var disposed = false
    private var loaded = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = StoryTreeCellRenderer(player)
        tree.emptyText.text = "No stories yet"
        tree.emptyText.appendSecondaryText("Ask your agent: /code-story \"<request>\"", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
        installStepOpening()
        tree.addTreeSelectionListener { showSelectedDetails() }
        setContent(JPanel(BorderLayout()).apply {
            add(OnePixelSplitter(true, "CodeStory.detailsProportion", 0.5f).apply {
                firstComponent = ScrollPaneFactory.createScrollPane(tree, true)
                secondComponent = ScrollPaneFactory.createScrollPane(details, true)
            }, BorderLayout.CENTER)
            add(cardContainer, BorderLayout.SOUTH)
        })
        toolbar = createToolbar()
        PopupHandler.installPopupMenu(tree, contextActions, "CodeStory.StoryMenu")
        project.messageBus.connect(this).subscribe(StoryListListener.TOPIC, StoryListListener { reload(it) })
        project.messageBus.connect(this).subscribe(StoryPlaybackListener.TOPIC, StoryPlaybackListener { updatePlayback() })
        updatePlayback()
        StoryGitMetadata.EP.extensionList.forEach { it.subscribe(project, this) { reload() } }
        reload(CodeStoryService.getInstance(project).lastShownStoryId)
    }

    /**
     * Reads the stories in the background, then shows them and selects [selectStoryId]. The change
     * information of stories with a base follows, because it runs Git for each of them.
     */
    fun reload(selectStoryId: String? = null) {
        loader.execute {
            val stories = try {
                CodeStoryService.getInstance(project).stories()
            } catch (e: Exception) {
                LOG.warn("Code Story cannot read the stories", e)
                return@execute
            }
            val git = stories.map { it.root }.distinct().associateWith { CodeStoryService.getInstance(project).currentGit(it) }
            onEdt { currentGit = git; showStories(stories, selectStoryId) }
            val changes = stories.mapNotNull { stored -> changesOf(stored)?.let { stored.id to it } }.toMap()
            onEdt { showChanges(changes) }
        }
    }

    /** Completes when the stories that were requested before are shown, with their change information. */
    @TestOnly
    internal fun loaded(): Future<*> = loader.submit {}

    private fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeLater(
        action, ModalityState.any(), Condition<Any?> { disposed || project.isDisposed },
    )

    /** A Git problem leaves the story without change information, and the list still shows it. */
    private fun changesOf(stored: StoredStory): StoryChanges? = try {
        StoryChangeInfo.getInstance(project).changes(stored)
    } catch (e: Exception) {
        if (e is ControlFlowException) throw e
        LOG.warn("Code Story cannot read the changes of ${stored.id}", e)
        null
    }

    /** Adds the badges and "Show diff" to the stories in the tree. */
    private fun showChanges(changes: Map<String, StoryChanges>) {
        this.changes = changes
        for (storyNode in storyNodes()) {
            val stored = (storyNode.userObject as StoryItem).stored
            val storyChanges = changesFor(stored)
            storyNode.userObject = StoryItem(stored, storyChanges)
            treeModel.nodeChanged(storyNode)
            for (stepNode in storyNode.children().toList().map { it as DefaultMutableTreeNode }) {
                stepNode.userObject = StepItem(stored, (stepNode.userObject as StepItem).index, storyChanges)
                treeModel.nodeChanged(stepNode)
            }
        }
        showSelectedDetails()
    }

    /** The change information that was found for this version of the story's base, if any. */
    private fun changesFor(stored: StoredStory): StoryChanges? = changes[stored.id]?.takeIf { it.baseRef == stored.story.baseRef }

    /** Replaces the tree content. Keeps open stories open and the selection, if the story is still there. */
    fun showStories(stories: List<StoredStory>, selectStoryId: String? = null) {
        val initialLoad = !loaded
        val expanded = storyNodes().filter { tree.isExpanded(TreePath(it.path)) }.map { storyId(it) }.toSet()
        val selected = selectedKey()

        rootNode.removeAllChildren()
        for (stored in stories) {
            val storyChanges = changesFor(stored)
            val storyNode = DefaultMutableTreeNode(StoryItem(stored, storyChanges))
            stored.story.steps.indices.forEach { storyNode.add(DefaultMutableTreeNode(StepItem(stored, it, storyChanges), false)) }
            rootNode.add(storyNode)
        }
        treeModel.reload()

        val newest = stories.firstOrNull()?.id
        for (node in storyNodes()) {
            val id = storyId(node)
            if (id in expanded || id == selectStoryId || (!loaded && id == newest)) tree.expandPath(TreePath(node.path))
        }
        loaded = true

        val toSelect = selectStoryId?.let { findNode(it, null) }
            ?: selected?.let { (id, step) -> findNode(id, step) }
            ?: if (selected == null) newest?.let { findNode(it, null) } else null
        if (toSelect != null) TreeUtil.selectPath(tree, TreePath(toSelect.path))
        if ((selectStoryId != null && player.currentStory?.id == selectStoryId) || (initialLoad && player.isPlaying)) selectPlayingStep()
        showSelectedDetails()
        updatePlayback()
    }

    override fun dispose() {
        disposed = true
        loader.shutdownNow()
        cardContainer.removeAll()
    }

    private fun installStepOpening() {
        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                if (event.button != MouseEvent.BUTTON1) return false
                val item = tree.getPathForLocation(event.x, event.y)?.let { stepItem(it) } ?: return false
                openStep(item)
                return true
            }
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode != KeyEvent.VK_ENTER) return
                val item = tree.selectionPath?.let { stepItem(it) } ?: return
                openStep(item)
                event.consume()
            }
        })
    }

    private fun openStep(item: StepItem) {
        details.showStep(item)
        player.play(item.stored, item.index)
    }

    private fun showSelectedDetails() {
        when (val item = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject) {
            is StoryItem -> details.showStory(item.stored, currentGit[item.stored.root], item.changes)
            is StepItem -> details.showStep(item)
            else -> details.clear()
        }
    }

    private fun createToolbar() = ActionManager.getInstance()
        .createActionToolbar("CodeStory.ToolWindow", DefaultActionGroup().apply {
            add(PlayAction())
            add(ActionManager.getInstance().getAction("CodeStory.PreviousStep"))
            add(ActionManager.getInstance().getAction("CodeStory.NextStep"))
            add(ActionManager.getInstance().getAction("CodeStory.StopStory"))
            addSeparator()
            add(RefreshAction())
        }, true)
        .also { it.targetComponent = this }
        .component

    private inner class RefreshAction : DumbAwareAction("Refresh", "Read the stories again", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) = reload()
    }

    private inner class PlayAction : DumbAwareAction("Play", "Continue the selected story", AllIcons.Actions.Execute) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = selectedStory() != null }
        override fun actionPerformed(e: AnActionEvent) { selectedStory()?.let { player.play(it) } }
    }

    private inner class RestartAction : DumbAwareAction("Restart", "Play the selected story from step 1", AllIcons.Actions.Restart) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = selectedStory() != null }
        override fun actionPerformed(e: AnActionEvent) { selectedStory()?.let { player.restart(it) } }
    }

    /** Only for steps of a story about a change: stories without a base show no "Show diff". */
    private inner class ShowDiffAction : DumbAwareAction("Show Diff", "Compare the step's file at the story's base commit with the current file", AllIcons.Actions.Diff) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = selectedStep()?.canShowDiff == true }
        override fun actionPerformed(e: AnActionEvent) { selectedStep()?.takeIf { it.canShowDiff }?.let(::showDiff) }
    }

    private fun showDiff(item: StepItem) {
        // While the story plays, its live markers have the newest lines of the step.
        val stored = player.currentStory?.takeIf { it.id == item.stored.id && it.story.steps.getOrNull(item.index)?.id == item.step.id } ?: item.stored
        StoryChangeInfo.getInstance(project).showDiff(stored, item.index)
    }

    private fun selectedStep(): StepItem? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? StepItem

    private fun selectedStory(): StoredStory? = when (val item = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject) {
        is StoryItem -> item.stored
        is StepItem -> item.stored
        else -> null
    }

    private fun selectPlayingStep() {
        val stored = player.currentStory ?: return
        val item = StepItem(stored, player.stepIndex, changesFor(stored))
        findNode(stored.id, item.step.id)?.let { TreeUtil.selectPath(tree, TreePath(it.path)) }
        details.showStep(item)
    }

    private fun updatePlayback() {
        if (player.playbackVersion != playbackVersion) {
            playbackVersion = player.playbackVersion
            if (player.isPlaying) selectPlayingStep()
        }
        val fallback = player.presentation?.takeIf { it.inToolWindow && !it.cardDismissed }?.card
        if (cardContainer.components.singleOrNull() !== fallback) {
            cardContainer.removeAll()
            if (fallback != null) cardContainer.add(fallback, BorderLayout.CENTER)
            cardContainer.revalidate()
            cardContainer.repaint()
        }
        tree.repaint()
    }

    private fun storyNodes(): List<DefaultMutableTreeNode> = rootNode.children().toList().map { it as DefaultMutableTreeNode }

    private fun storyId(node: DefaultMutableTreeNode): String = (node.userObject as StoryItem).stored.id

    private fun stepItem(path: TreePath): StepItem? = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? StepItem

    /** The selected story ID and, for a step, its stable ID. */
    private fun selectedKey(): Pair<String, String?>? = when (val item = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject) {
        is StoryItem -> item.stored.id to null
        is StepItem -> item.stored.id to item.step.id
        else -> null
    }

    private fun findNode(storyId: String, stepId: String?): DefaultMutableTreeNode? {
        val storyNode = storyNodes().firstOrNull { storyId(it) == storyId } ?: return null
        if (stepId == null) return storyNode
        return storyNode.children().toList().map { it as DefaultMutableTreeNode }
            .firstOrNull { (it.userObject as StepItem).step.id == stepId } ?: storyNode
    }

    companion object {
        const val NOTIFICATION_GROUP = "Code Story"
        private val LOG = logger<StoryListPanel>()
    }
}

private class StoryTreeCellRenderer(private val player: StoryPlayer) : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        // The text comes from story files, so it is shown as plain text, never as HTML.
        when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
            is StoryItem -> {
                icon = when (item.stored.story.type) {
                    StoryType.CHANGE -> AllIcons.Actions.Diff
                    StoryType.EXPLORATION -> AllIcons.Actions.Find
                    StoryType.BUG_TRACE -> AllIcons.Actions.StartDebugger
                    StoryType.REVIEW -> AllIcons.Actions.Checked
                }
                append(item.stored.story.title)
                append("  ${item.stored.date}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                val git = item.stored.story.git
                (git.branch ?: if (git.commit != null) "Detached HEAD" else null)
                    ?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                staleSteps(item.stored)?.let { append("  $it", WARNING) }
                toolTipText = item.stored.id
            }
            is StepItem -> {
                val location = item.step.location
                val attributes = if (player.currentStory?.id == item.stored.id && player.stepIndex == item.index)
                    SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES
                append("${item.index + 1}. ${item.step.title}", attributes)
                if (location != null) append("  ${location.file.substringAfterLast('/')}:${lines(location)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                item.change?.let { change ->
                    val color = if (change == StepChange.ADDED) FileStatus.ADDED.color else FileStatus.MODIFIED.color
                    append("  ${change.badge}", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color))
                }
                when (item.stored.status(item.index)) {
                    StepStatus.CODE_CHANGED -> append("  code changed", WARNING)
                    StepStatus.LOST -> append("  lost", WARNING)
                    StepStatus.OK, StepStatus.MOVED -> Unit
                }
                if (player.wasVisited(item.stored.id, item.index)) append("  ✓", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = listOfNotNull(location?.file, item.change?.let { changeTip(it, item.changes) }).joinToString(" · ").ifEmpty { null }
            }
        }
    }

    private fun changeTip(change: StepChange, changes: StoryChanges?): String {
        val since = changes?.commit?.take(10)?.let { " since $it" }.orEmpty()
        return if (change == StepChange.ADDED) "Added$since" else "Modified$since"
    }

    private fun lines(location: Location): String =
        if (location.startLine == location.endLine) "${location.startLine}" else "${location.startLine}–${location.endLine}"

    /** For example "1 lost, 2 code changed", or `null` when no step is lost or changed. */
    private fun staleSteps(stored: StoredStory): String? {
        val statuses = stored.story.steps.indices.map { stored.status(it) }
        return listOfNotNull(
            statuses.count { it == StepStatus.LOST }.takeIf { it > 0 }?.let { "$it lost" },
            statuses.count { it == StepStatus.CODE_CHANGED }.takeIf { it > 0 }?.let { "$it code changed" },
        ).joinToString(", ").ifEmpty { null }
    }

    private companion object {
        val WARNING = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.namedColor("Label.warningForeground", JBColor(0xA35F00, 0xD9A343)))
    }
}
