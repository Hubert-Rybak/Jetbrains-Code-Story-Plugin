package io.github.hubertrybak.codestory

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.SimpleColoredComponent
import com.intellij.util.xmlb.XmlSerializer
import io.github.hubertrybak.codestory.core.ShowStatus
import io.github.hubertrybak.codestory.core.StoryFolder
import io.github.hubertrybak.codestory.core.StoryFormat
import io.github.hubertrybak.codestory.core.StoryJson.quoted
import io.github.hubertrybak.codestory.core.StoryJson.step
import io.github.hubertrybak.codestory.core.StoryJson.story
import io.github.hubertrybak.codestory.core.StoryMarkdownExport
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.ui.RepositoryItem
import io.github.hubertrybak.codestory.ui.StoryFolderItem
import io.github.hubertrybak.codestory.ui.StoryItem
import io.github.hubertrybak.codestory.ui.StoryListPanel
import io.github.hubertrybak.codestory.ui.StoryPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.awt.datatransfer.DataFlavor
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** Management actions and externally arriving files, using real project storage and the IDE player. */
class StoryManagementTest : HeavyPlatformTestCase() {
    private lateinit var root: Path
    private var panel: StoryListPanel? = null
    private val service get() = CodeStoryService.getInstance(project)
    private val player get() = StoryPlayer.getInstance(project)

    override fun setUp() {
        super.setUp()
        root = Path.of(project.basePath!!)
        write(root.resolve("src/App.kt"), (1..10).joinToString("\n", postfix = "\n") { "line $it" })
    }

    override fun tearDown() {
        try {
            StoryFileWatcher.getInstance(project).dispose()
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            player.stop()
            panel?.let(Disposer::dispose)
            FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
        } catch (e: Throwable) { addSuppressedException(e) }
        finally { super.tearDown() }
    }

    fun `test share and unshare preserve bytes ID selection and playback position`() {
        val stored = show(story(step(), step(start = "5", end = "6")))
        await { player.currentStory?.key == stored.key }
        player.next()
        val original = Files.readString(stored.file)
        openPanel()
        select(stored)
        perform(panel!!.toolbarActions, "Share")
        val shared = StoryFolder(root).sharedDir.resolve(stored.file.fileName)
        await { Files.exists(shared) && item(stored.key)?.stored?.folder == "shared" && player.currentStory?.file == shared }
        assertFalse(Files.exists(stored.file))
        assertEquals(original, Files.readString(shared))
        assertEquals(stored.id, player.currentStory!!.id)
        assertEquals(1, player.stepIndex)
        assertFalse(item(stored.key)!!.stored.isNew)
        assertEquals(stored.key, ((panel!!.tree.lastSelectedPathComponent as DefaultMutableTreeNode).userObject as StoryItem).stored.key)

        // The same action becomes Unshare in the context menu.
        perform(panel!!.contextActions, "Share", "Unshare")
        await { Files.exists(stored.file) && item(stored.key)?.stored?.folder == "local" && player.currentStory?.file == stored.file }
        assertFalse(Files.exists(shared))
        assertEquals(original, Files.readString(stored.file))
        assertTrue(Files.exists(StoryFolder(root).localDir.resolve(".gitignore")))
        assertEquals(1, player.stepIndex)
    }

    fun `test delete confirms and cancellation keeps the story`() {
        val stored = show(story(step()))
        await { player.isPlaying }
        openPanel()
        select(stored)
        var confirmations = 0
        TestDialogManager.setTestDialog { confirmations++; Messages.NO }
        perform(panel!!.contextActions, "Delete")
        assertEquals(1, confirmations)
        assertTrue(Files.exists(stored.file))
        assertTrue(player.isPlaying)

        TestDialogManager.setTestDialog { confirmations++; Messages.YES }
        perform(panel!!.toolbarActions, "Delete")
        await { !Files.exists(stored.file) && item(stored.key) == null && !player.isPlaying }
        assertEquals(2, confirmations)
        assertEmpty(service.stories())
    }

    fun `test arriving shared file is listed as new without auto play and read state persists`() {
        val stored = show(story(step()))
        await { player.isPlaying }
        player.stop()
        val arrivalId = "2026-10-01-reviewer-story"
        val arrival = StoryFolder(root).sharedDir.resolve(arrivalId + StoryFormat.FILE_SUFFIX)
        openPanel()
        write(arrival, StoryFormat.encode(stored.story.copy(title = "Reviewer story"), arrivalId))
        await { items().any { it.stored.id == arrivalId } }
        val incoming = items().single { it.stored.id == arrivalId }.stored
        assertTrue(incoming.isNew)
        assertFalse(player.isPlaying)
        val node = nodes().single { (it.userObject as? StoryItem)?.stored?.id == arrivalId }
        val renderer = panel!!.tree.cellRenderer.getTreeCellRendererComponent(panel!!.tree, node, false, false, false, 0, false)
        assertTrue((renderer as SimpleColoredComponent).getCharSequence(false).toString().contains("  new"))
        val bytes = Files.readString(arrival)

        // Reloading the panel alone must not clear the marker or start playback.
        Disposer.dispose(panel!!)
        openPanel()
        assertTrue(item(incoming.key)!!.stored.isNew)
        assertFalse(player.isPlaying)
        select(incoming)
        perform(panel!!.toolbarActions, "Play")
        await { item(incoming.key)?.stored?.isNew == false }
        assertEquals(incoming.key, player.currentStory!!.key)
        assertEquals(bytes, Files.readString(arrival))
        val persisted = XmlSerializer.serialize(project.service<StoryReaderState>().state)
        assertFalse(StoryReaderState().apply { loadState(XmlSerializer.deserialize(persisted, StoryReaderState.ReadStories::class.java)) }.isNew(incoming))
    }

    fun `test toolbar and context menu copy the complete story even with a step selected`() {
        val stored = show(story(step(), step(location = null)))
        openPanel()
        select(stored)
        for (actions in listOf(panel!!.toolbarActions, panel!!.contextActions)) {
            perform(actions, "Copy as Markdown")
            assertEquals(StoryMarkdownExport.render(stored.story), CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))
            val storyNode = nodes().single { (it.userObject as? StoryItem)?.stored?.key == stored.key }
            panel!!.tree.selectionPath = TreePath((storyNode.getChildAt(0) as DefaultMutableTreeNode).path)
        }
    }

    fun `test repository groups and duplicate IDs keep files and reader state separate`() {
        val first = repository("first")
        val second = repository("second")
        mapRepositories(first, second)
        val a = show(story(step(), step(start = "5", end = "6")), first)
        val b = show(story(step(), step(start = "5", end = "6")), second)
        assertEquals(a.id, b.id)
        await { player.currentStory?.key == b.key }
        player.stop()
        openPanel()
        assertEquals(listOf(first, second), nodes().mapNotNull { (it.userObject as? RepositoryItem)?.root })
        assertEquals(listOf("Local", "Shared", "Local", "Shared"), nodes().mapNotNull { (it.userObject as? StoryFolderItem)?.name })
        for (stored in listOf(a, b)) {
            val node = nodes().single { (it.userObject as? StoryItem)?.stored?.key == stored.key }
            assertEquals(stored.root, ((node.parent as DefaultMutableTreeNode).userObject as StoryFolderItem).root)
        }
        // Startup may have watched the plain project before VCS mappings were configured.
        assertFalse(Files.exists(root.resolve(".codestory/local")))
        assertFalse(Files.exists(root.resolve(".codestory/shared")))
        select(a)
        perform(panel!!.toolbarActions, "Share")
        await { item(a.key)?.stored?.folder == "shared" }
        assertTrue(Files.exists(b.file))
        assertEquals(setOf(first.toString(), second.toString()), service.list().map { it.repositoryPath }.toSet())
        assertNotNull(service.get(a.id, first.toString()))
        assertNotNull(service.get(b.id, second.toString()))

        player.play(a, 1)
        player.play(b)
        assertEquals(0, player.stepIndex)
        assertFalse(player.wasVisited(b, 1))
        player.play(a)
        assertEquals(1, player.stepIndex)
    }

    fun `test mixed repositories are rejected and unambiguous paths select their repository`() {
        val first = repository("first", "First.kt")
        val second = repository("second", "Second.kt")
        mapRepositories(first, second)
        for (json in listOf(
            story(step(file = quoted("src/First.kt")), step(file = quoted("src/Second.kt"))),
            story(step(file = quoted("first/src/First.kt")), step(file = quoted("second/src/Second.kt"))),
        )) {
            val report = showReport(json)
            assertEquals(ShowStatus.REJECTED, report.status)
            assertEquals("cross-repository", report.errors.single().code)
        }
        assertFalse(Files.exists(first.resolve(".codestory")))
        assertFalse(Files.exists(second.resolve(".codestory")))
        val stored = show(story(step(file = quoted("src/Second.kt"))))
        assertEquals(second, stored.root)
    }

    fun `test ambiguous paths and text-only stories require a repository and replacement never guesses`() {
        val first = repository("first")
        val second = repository("second")
        mapRepositories(first, second)
        for (json in listOf(story(step()), story(step(location = null)))) {
            assertEquals("repository-required", showReport(json).errors.single().code)
        }
        val a = show(story(step()), first)
        show(story(step()), second)
        val report = runBlocking { withContext(Dispatchers.IO) { service.show(Json.parseToJsonElement(story(step())), a.id) } }
        assertEquals("repository-required", report.errors.single().code)
    }

    fun `test unmapped repositories are rejected with a report`() {
        val first = repository("first")
        val second = repository("second")
        val unmapped = repository("unmapped", "Other.kt")
        mapRepositories(first, second)
        assertEquals("invalid-repository", showReport(story(step()), unmapped).errors.single().code)
        assertEquals("invalid-repository", showReport(story(step(file = quoted("unmapped/src/Other.kt")))).errors.single().code)
        assertEmpty(service.stories())
    }

    private fun showReport(json: String, repository: Path? = null) = runBlocking {
        withContext(Dispatchers.IO) { service.show(Json.parseToJsonElement(json), repositoryPath = repository?.toString()) }
    }

    private fun show(json: String, repository: Path? = null): StoredStory {
        val report = showReport(json, repository)
        assertEquals(report.errors.toString(), ShowStatus.ACCEPTED, report.status)
        return service.stories().single { it.id == report.storyId && (repository == null || it.root == repository) }
    }

    private fun openPanel() {
        StoryFileWatcher.getInstance(project).start()
        panel = StoryListPanel(project)
        val loaded = panel!!.loaded()
        await { loaded.isDone && nodes().any { it.userObject is StoryFolderItem } }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun nodes() = (panel!!.tree.model.root as DefaultMutableTreeNode).preorderEnumeration().toList().filterIsInstance<DefaultMutableTreeNode>()
    private fun items() = nodes().mapNotNull { it.userObject as? StoryItem }
    private fun item(key: String) = items().singleOrNull { it.stored.key == key }
    private fun select(stored: StoredStory) {
        panel!!.tree.selectionPath = TreePath(nodes().single { (it.userObject as? StoryItem)?.stored?.key == stored.key }.path)
    }

    private fun perform(actions: DefaultActionGroup, name: String, label: String = name) {
        val action = actions.getChildActionsOrStubs().single { it.templatePresentation.text == name }
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
        assertEquals(label, event.presentation.text)
        action.actionPerformed(event)
    }

    private fun await(condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("story state did not update", condition, 20)
    private fun repository(name: String, file: String = "App.kt"): Path = root.resolve(name).also {
        write(it.resolve("src/$file"), (1..10).joinToString("\n", postfix = "\n") { "line $it" })
        GitFixture(it).init()
    }
    private fun mapRepositories(vararg roots: Path) {
        ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(roots.map { VcsDirectoryMapping(it.toString(), "Git") })
    }
    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }
}
