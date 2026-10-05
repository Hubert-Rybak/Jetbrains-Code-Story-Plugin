package io.github.hubertrybak.codestory.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Condition
import com.intellij.ui.ClickListener
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.tree.TreeUtil
import io.github.hubertrybak.codestory.CodeStoryService
import io.github.hubertrybak.codestory.StoryListListener
import io.github.hubertrybak.codestory.core.Location
import io.github.hubertrybak.codestory.core.StoredStory
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** A story row in the tree. */
class StoryItem(val stored: StoredStory)

/** A step row in the tree; [index] is 0-based. */
class StepItem(val stored: StoredStory, val index: Int) {
    val step get() = stored.story.steps[index]
}

/** The content of the Code Story tool window: the stories and their steps. A click on a step opens its code. */
class StoryListPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val rootNode = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(rootNode)
    val tree: Tree = Tree(treeModel)
    private val loader = AppExecutorUtil.createBoundedApplicationPoolExecutor("Code Story story list", 1)

    @Volatile
    private var disposed = false
    private var loaded = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = StoryTreeCellRenderer()
        tree.emptyText.text = "No stories yet"
        tree.emptyText.appendSecondaryText("Ask your agent: /code-story \"<request>\"", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
        installStepOpening()
        setContent(ScrollPaneFactory.createScrollPane(tree, true))
        toolbar = createToolbar()
        project.messageBus.connect(this).subscribe(StoryListListener.TOPIC, StoryListListener { reload(it) })
        reload(CodeStoryService.getInstance(project).lastShownStoryId)
    }

    /** Reads the stories in the background, then shows them and selects [selectStoryId]. */
    fun reload(selectStoryId: String? = null) {
        loader.execute {
            val stories = try {
                CodeStoryService.getInstance(project).stories()
            } catch (e: Exception) {
                LOG.warn("Code Story cannot read the stories", e)
                return@execute
            }
            ApplicationManager.getApplication().invokeLater(
                { showStories(stories, selectStoryId) },
                ModalityState.any(),
                Condition<Any?> { disposed || project.isDisposed },
            )
        }
    }

    /** Replaces the tree content. Keeps open stories open and the selection, if the story is still there. */
    fun showStories(stories: List<StoredStory>, selectStoryId: String? = null) {
        val expanded = storyNodes().filter { tree.isExpanded(TreePath(it.path)) }.map { storyId(it) }.toSet()
        val selected = selectedKey()

        rootNode.removeAllChildren()
        for (stored in stories) {
            val storyNode = DefaultMutableTreeNode(StoryItem(stored))
            stored.story.steps.indices.forEach { storyNode.add(DefaultMutableTreeNode(StepItem(stored, it), false)) }
            rootNode.add(storyNode)
        }
        treeModel.reload()

        val newest = stories.firstOrNull()?.id
        for (node in storyNodes()) {
            val id = storyId(node)
            if (id in expanded || id == selectStoryId || (!loaded && id == newest)) tree.expandPath(TreePath(node.path))
        }
        loaded = true

        val toSelect = selectStoryId?.let { findNode(it, null) } ?: selected?.let { (id, step) -> findNode(id, step) }
        if (toSelect != null) TreeUtil.selectPath(tree, TreePath(toSelect.path))
    }

    override fun dispose() {
        disposed = true
        loader.shutdownNow()
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
        val location = item.step.location
        if (StepNavigator.getInstance(project).open(item.stored.root, location) == null) {
            NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification("Code Story cannot open ${location.file}.", NotificationType.WARNING)
                .notify(project)
        }
    }

    private fun createToolbar() = ActionManager.getInstance()
        .createActionToolbar("CodeStory.ToolWindow", DefaultActionGroup(RefreshAction()), true)
        .also { it.targetComponent = this }
        .component

    private inner class RefreshAction : DumbAwareAction("Refresh", "Read the stories again", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) = reload()
    }

    private fun storyNodes(): List<DefaultMutableTreeNode> = rootNode.children().toList().map { it as DefaultMutableTreeNode }

    private fun storyId(node: DefaultMutableTreeNode): String = (node.userObject as StoryItem).stored.id

    private fun stepItem(path: TreePath): StepItem? = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? StepItem

    /** The selected story ID and, for a step, its index. */
    private fun selectedKey(): Pair<String, Int?>? = when (val item = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject) {
        is StoryItem -> item.stored.id to null
        is StepItem -> item.stored.id to item.index
        else -> null
    }

    private fun findNode(storyId: String, stepIndex: Int?): DefaultMutableTreeNode? {
        val storyNode = storyNodes().firstOrNull { storyId(it) == storyId } ?: return null
        if (stepIndex == null) return storyNode
        return storyNode.children().toList().map { it as DefaultMutableTreeNode }.getOrNull(stepIndex) ?: storyNode
    }

    companion object {
        const val NOTIFICATION_GROUP = "Code Story"
        private val LOG = logger<StoryListPanel>()
    }
}

private class StoryTreeCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        // The text comes from story files, so it is shown as plain text, never as HTML.
        when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
            is StoryItem -> {
                icon = AllIcons.Toolwindows.Documentation
                append(item.stored.story.title)
                append("  ${item.stored.date}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = item.stored.id
            }
            is StepItem -> {
                val location = item.step.location
                append("${item.index + 1}. ${item.step.title}")
                append("  ${location.file.substringAfterLast('/')}:${lines(location)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = location.file
            }
        }
    }

    private fun lines(location: Location): String =
        if (location.startLine == location.endLine) "${location.startLine}" else "${location.startLine}–${location.endLine}"
}
