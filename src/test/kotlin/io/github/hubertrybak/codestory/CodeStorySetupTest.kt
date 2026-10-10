package io.github.hubertrybak.codestory

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.util.xmlb.XmlSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JCheckBox

class CodeStorySetupTest : HeavyPlatformTestCase() {
    fun `test setup reports registered tools and installed versions at both levels`() {
        val user = Files.createTempDirectory("code-story-user")
        val root = Path.of(project.basePath!!)
        try {
            val initial = CodeStorySetup.getInstance().status(project, user)
            assertTrue(initial.mcp.available)
            assertEquals(setOf("code_story_guide", "code_story_show", "code_story_list", "code_story_get", "code_story_feedback", "code_story_patch", "code_story_reply"), initial.mcp.tools.toSet())
            assertTrue(initial.skills.isEmpty())
            assertEquals(CodeStorySkill.directories(user), initial.installDirectories)
            CodeStorySkill.install(user)
            CodeStorySkill.install(root)
            Files.writeString(CodeStorySkill.directories(root).first().resolve("version.txt"), "0.0.0")
            val status = CodeStorySetup.getInstance().status(project, user)
            assertEquals(4, status.skills.size)
            assertTrue(status.text().contains("0.0.0 (update available)"))
            assertTrue(status.text().contains(user.toString()))
            assertTrue(status.text().contains(root.toString()))
            assertTrue(status.text().contains(CodeStorySkill.version))
        } finally { user.toFile().deleteRecursively() }
    }

    fun `test setup offers optional hook snippets without installing hooks`() {
        val status = CodeStorySetup.getInstance().status(project)
        assertTrue(status.text().contains("pending-feedback.json"))
        assertTrue(status.text().contains("never installs hooks"))
        val snippets = CodeStoryFeedbackHooks.snippets
        assertTrue(snippets.contains("~/.claude/settings.json"))
        assertTrue(snippets.contains("~/.codex/hooks.json"))
        assertTrue(snippets.contains(".github/hooks/code-story.json"))
        assertTrue(snippets.contains("SessionStart") && snippets.contains("UserPromptSubmit") && snippets.contains("sessionStart"))
        val root = Path.of(project.basePath!!)
        assertFalse(Files.exists(root.resolve(".codex")))
        assertFalse(Files.exists(root.resolve(".claude")))
        assertFalse(Files.exists(root.resolve(".github/hooks")))
    }

    fun `test setup remains available without MCP`() {
        ExtensionTestUtil.maskExtensions(StoryMcpSupport.EP, emptyList(), testRootDisposable)
        val unavailable = CodeStorySetup.getInstance().status(project)
        assertFalse(unavailable.mcp.available)
        assertFalse(unavailable.mcp.enabled)
        assertTrue(unavailable.mcp.tools.isEmpty())
        assertTrue(unavailable.text().contains("disabled or unavailable"))
    }

    fun `test setup reports on and off accurately`() {
        var enabled = false
        ExtensionTestUtil.maskExtensions(StoryMcpSupport.EP, listOf(object : StoryMcpSupport {
            override fun status() = McpSetupStatus(true, enabled, listOf("code_story_show"))
        }), testRootDisposable)
        assertTrue(CodeStorySetup.getInstance().status(project).text().contains("MCP server: off"))
        enabled = true
        assertTrue(CodeStorySetup.getInstance().status(project).text().contains("MCP server: on"))
        assertTrue(enabled) // Reading setup never changes the server state.
    }

    fun `test install scope defaults to user and persists from the settings page`() {
        val settings = CodeStorySettings.getInstance()
        val original = settings.state
        val configurable = CodeStoryConfigurable()
        try {
            settings.loadState(CodeStorySettings.Options())
            assertFalse(settings.state.projectSkillInstallation)
            fun checkboxes(container: Container): List<JCheckBox> = container.components.flatMap {
                if (it is JCheckBox) listOf(it) else if (it is Container) checkboxes(it) else emptyList()
            }
            val checkbox = checkboxes(configurable.createComponent() as Container).single { it.text.contains("current project") }
            checkbox.isSelected = true
            assertTrue(configurable.isModified)
            configurable.apply()
            assertFalse(configurable.isModified)
            settings.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(settings.state), CodeStorySettings.Options::class.java))
            assertTrue(settings.state.projectSkillInstallation)
            assertEquals(CodeStorySkill.directories(Path.of(project.basePath!!)), CodeStorySetup.getInstance().status(project).installDirectories)
        } finally { configurable.disposeUIResources(); settings.loadState(original) }
        assertNotNull(ActionManager.getInstance().getAction("CodeStory.InstallSkill"))
        assertNotNull(ActionManager.getInstance().getAction("CodeStory.CheckSetup"))
    }

    fun `test first run shows one notification with both setup actions`() {
        val settings = CodeStorySettings.getInstance()
        val original = settings.state
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val notifications = CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) { if (notification.groupId == "Code Story") notifications.add(notification) }
        })
        try {
            settings.loadState(CodeStorySettings.Options())
            val setup = CodeStorySetup(scope)
            setup.projectOpened(project)
            PlatformTestUtil.waitWithEventsDispatching("No onboarding notification", { notifications.isNotEmpty() }, 10)
            assertEquals(1, notifications.size)
            val notification = notifications.single()
            assertTrue(notification.content.contains("1. Install"))
            assertTrue(notification.content.contains("2. Switch on"))
            assertEquals(listOf("Install Code Story skill", "Open MCP Server settings"), notification.actions.map { it.templatePresentation.text })
            assertTrue(settings.state.setupNotificationShown)
            val again = setup.projectOpened(project)
            PlatformTestUtil.waitWithEventsDispatching("Second startup did not finish", { again.isCompleted }, 10)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals(1, notifications.size)
        } finally {
            scope.cancel()
            notifications.forEach { it.expire() }
            settings.loadState(original)
        }
    }

    fun `test a plugin update offers once and updates installed project copies on request`() {
        val settings = CodeStorySettings.getInstance()
        val original = settings.state
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val notifications = CopyOnWriteArrayList<Notification>()
        val root = Path.of(project.basePath!!)
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) { if (notification.groupId == "Code Story") notifications.add(notification) }
        })
        ExtensionTestUtil.maskExtensions(StoryMcpSupport.EP, listOf(object : StoryMcpSupport {
            override fun status() = McpSetupStatus(true, true, emptyList())
        }), testRootDisposable)
        try {
            settings.loadState(CodeStorySettings.Options().apply { setupNotificationShown = true })
            CodeStorySkill.install(root).forEach { Files.writeString(it.resolve("version.txt"), "0.0.0") }
            val setup = CodeStorySetup(scope)
            setup.projectOpened(project)
            PlatformTestUtil.waitWithEventsDispatching("No skill update offer", { notifications.isNotEmpty() }, 10)
            val offer = notifications.single()
            assertEquals("Update Code Story skill", offer.title)
            assertTrue(CodeStorySkill.installed(root, null).all { it.version == "0.0.0" })
            val again = setup.projectOpened(project)
            PlatformTestUtil.waitWithEventsDispatching("Second startup did not finish", { again.isCompleted }, 10)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals(1, notifications.size)
            val action = offer.actions.single() as NotificationAction
            action.actionPerformed(TestActionEvent.createTestEvent(action), offer)
            PlatformTestUtil.waitWithEventsDispatching("Skills did not update", {
                CodeStorySkill.installed(root, null).all { it.complete && it.version == CodeStorySkill.version }
            }, 10)
        } finally {
            scope.cancel()
            notifications.forEach { it.expire() }
            settings.loadState(original)
        }
    }
}
