package io.github.hubertrybak.codestory

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import io.github.hubertrybak.codestory.core.StoryFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

data class CodeStorySetupStatus(
    val mcp: McpSetupStatus,
    val skills: List<CodeStorySkill.Installation>,
    val installDirectories: List<Path>,
    val bundledVersion: String,
) {
    fun text(): String = buildString {
        appendLine("MCP server: ${if (mcp.enabled) "on" else "off"}${if (!mcp.available) " (MCP Server plugin disabled or unavailable)" else ""}")
        appendLine("Code Story tools registered: ${mcp.tools.joinToString().ifEmpty { "none" }}")
        appendLine("Bundled skill version: $bundledVersion")
        if (skills.isEmpty()) appendLine("Code Story skill: not installed")
        for (skill in skills) {
            appendLine("Skill: ${skill.directory} — version ${skill.version ?: "unknown"}${if (!skill.complete) " (incomplete)" else ""}${if (skill.needsUpdate(bundledVersion)) " (update available)" else ""}")
        }
        appendLine("Install Code Story skill writes to:")
        installDirectories.forEach { appendLine(it) }
        if (!mcp.enabled) appendLine("The MCP server lets your agent send stories and receive validation reports. Enable it in Settings | Tools | MCP Server and use Auto-Configure. File drops work while it is off.")
        appendLine("Optional feedback hooks: copy snippets for Claude Code and Codex (session start and each prompt), and Copilot CLI (session start).")
        appendLine("They read .codestory/local/pending-feedback.json. The plugin never installs hooks.")
        appendLine("Automatic feedback listening is ${if (CodeStorySettings.getInstance().state.automaticFeedback) "on" else "off"}. Install the current skill so connected agents call code_story_listen after showing a story and after replying.")
        appendLine("A listening agent picks up each posted comment at once. If the agent has stopped, tell it /code-story feedback (\$code-story feedback in Codex).")
        append("Hooks only provide reminders. Copilot in Rider needs no hook for listening, but its agent must keep the listening tool call active.")
    }
}

object CodeStoryFeedbackHooks {
    val snippets: String get() = checkNotNull(javaClass.getResourceAsStream("/code-story/feedback-hooks.md")).bufferedReader().use { it.readText() }
}

@Service(Service.Level.APP)
class CodeStorySetup(private val scope: CoroutineScope) {
    private val reminders = ConcurrentHashMap.newKeySet<String>()

    fun status(project: Project?, userHome: Path = Path.of(System.getProperty("user.home"))): CodeStorySetupStatus {
        val root = project?.basePath?.let { StoryFolder.findRoot(Path.of(it)) }
        val base = if (CodeStorySettings.getInstance().state.projectSkillInstallation) {
            checkNotNull(root) { "Open a project to install the project-level skill." }
        } else userHome
        return CodeStorySetupStatus(StoryMcpSupport.status(), CodeStorySkill.installed(userHome, root), CodeStorySkill.directories(base), CodeStorySkill.version)
    }

    fun check(project: Project) = background(project) {
        val status = status(project)
        onEdt(project) {
            val choice = Messages.showDialog(project, status.text(), "Code Story setup",
                arrayOf("Close", "Copy feedback hooks"), 0, Messages.getInformationIcon())
            if (choice == 1) CopyPasteManager.getInstance().setContents(StringSelection(CodeStoryFeedbackHooks.snippets))
            if (!status.mcp.enabled) notifyMcp(project, status.mcp)
        }
    }

    fun install(project: Project) = background(project) {
        val base = if (CodeStorySettings.getInstance().state.projectSkillInstallation) {
            StoryFolder.findRoot(Path.of(checkNotNull(project.basePath)))
        } else Path.of(System.getProperty("user.home"))
        installed(project, CodeStorySkill.install(base))
    }

    private fun update(project: Project, skills: List<CodeStorySkill.Installation>) = background(project) {
        installed(project, CodeStorySkill.update(skills))
    }

    private fun installed(project: Project, paths: List<Path>) = onEdt(project) {
        notification("Code Story skill installed", "Version ${CodeStorySkill.version}<br>" + paths.joinToString("<br>") { StringUtil.escapeXmlEntities(it.toString()) }).notify(project)
    }

    /** One first-run balloon, then at most one MCP reminder per session and one offer per old copy. */
    fun projectOpened(project: Project) = background(project) {
        val status = status(project)
        onEdt(project) {
            val settings = CodeStorySettings.getInstance().state
            if (!settings.setupNotificationShown) {
                settings.setupNotificationShown = true
                reminders.add("mcp")
                notification("Set up Code Story", "1. Install the code-story skill for your agents.<br>2. Switch on Settings | Tools | MCP Server and use Auto-Configure.<br>The MCP server lets agents send stories and receive validation reports. File drops work while it is off.")
                    .addAction(NotificationAction.createSimple("Install Code Story skill") { install(project) })
                    .addAction(mcpSettingsAction(project, status.mcp))
                    .notify(project)
                return@onEdt
            } else if (!status.mcp.enabled && reminders.add("mcp")) {
                notifyMcp(project, status.mcp)
            }
            val outdated = status.skills.filter { it.needsUpdate(status.bundledVersion) }
            val newOffers = outdated.map { reminders.add("${it.directory}:${status.bundledVersion}") }
            if (newOffers.any { it }) {
                notification("Update Code Story skill", "The plugin bundles skill version ${status.bundledVersion}. Update the installed copies to use its current guide and format.")
                    .addAction(NotificationAction.createSimple("Update Code Story skill") { update(project, outdated) })
                    .notify(project)
            }
        }
    }

    private fun notifyMcp(project: Project, status: McpSetupStatus) {
        notification("Connect your agent to Code Story", "The MCP server lets your agent send stories and receive validation reports. ${if (status.available) "Switch it on in Settings | Tools | MCP Server, then use Auto-Configure." else "Enable the MCP Server plugin in Settings | Plugins, then open Settings | Tools | MCP Server."} File drops work while it is off.")
            .addAction(mcpSettingsAction(project, status)).notify(project)
    }

    private fun mcpSettingsAction(project: Project, status: McpSetupStatus) = NotificationAction.createSimple(
        if (status.available) "Open MCP Server settings" else "Open Plugins settings",
    ) { ShowSettingsUtil.getInstance().showSettingsDialog(project, if (status.available) "com.intellij.mcpserver.settings" else "preferences.pluginManager") }

    private fun background(project: Project, action: () -> Unit): Job =
        scope.launch(Dispatchers.IO) {
            if (project.isDisposed) return@launch
            try { action() }
            catch (e: IOException) {
                onEdt(project) { notification("Code Story setup failed", StringUtil.escapeXmlEntities(e.message ?: "Cannot access the skill files."), NotificationType.ERROR).notify(project) }
            }
        }

    private fun onEdt(project: Project, action: () -> Unit) = ApplicationManager.getApplication().invokeLater(action, ModalityState.any(), project.disposed)

    companion object {
        fun getInstance(): CodeStorySetup = service()
        private fun notification(title: String, text: String, type: NotificationType = NotificationType.INFORMATION) =
            NotificationGroupManager.getInstance().getNotificationGroup("Code Story").createNotification(title, text, type)
    }
}

class CodeStoryStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (project.basePath == null) return
        StoryFileWatcher.getInstance(project).start()
        if (!ApplicationManager.getApplication().isUnitTestMode) CodeStorySetup.getInstance().projectOpened(project)
    }
}

class InstallCodeStorySkillAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project?.basePath != null }
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { CodeStorySetup.getInstance().install(it) } }
}

class CheckCodeStorySetupAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project?.basePath != null }
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { CodeStorySetup.getInstance().check(it) } }
}
