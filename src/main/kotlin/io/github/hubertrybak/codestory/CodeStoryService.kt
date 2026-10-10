package io.github.hubertrybak.codestory

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import io.github.hubertrybak.codestory.core.DraftComment
import io.github.hubertrybak.codestory.core.ReportItem
import io.github.hubertrybak.codestory.core.ErrorCode
import io.github.hubertrybak.codestory.core.FeedbackReport
import io.github.hubertrybak.codestory.core.FeedbackListenReport
import io.github.hubertrybak.codestory.core.ReplyInput
import io.github.hubertrybak.codestory.core.ReplyIssue
import io.github.hubertrybak.codestory.core.ReplyReport
import io.github.hubertrybak.codestory.core.RootStoryFiles
import io.github.hubertrybak.codestory.core.ShowReport
import io.github.hubertrybak.codestory.core.StepStatus
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryAnchors
import io.github.hubertrybak.codestory.core.StoryFeedback
import io.github.hubertrybak.codestory.core.StoryFiles
import io.github.hubertrybak.codestory.core.StoryFolder
import io.github.hubertrybak.codestory.core.StoryFormat
import io.github.hubertrybak.codestory.core.StoryLimits
import io.github.hubertrybak.codestory.core.StoryListEntry
import io.github.hubertrybak.codestory.core.StoryPatch
import io.github.hubertrybak.codestory.core.StoryRevisions
import io.github.hubertrybak.codestory.core.StoryThreads
import io.github.hubertrybak.codestory.core.StoryWritingChecks
import io.github.hubertrybak.codestory.core.StoryGit
import io.github.hubertrybak.codestory.core.StoryValidator
import io.github.hubertrybak.codestory.core.TextFiles
import io.github.hubertrybak.codestory.core.ValidationResult
import io.github.hubertrybak.codestory.ui.CODEX_FEEDBACK_COMMAND
import io.github.hubertrybak.codestory.ui.FEEDBACK_COMMAND
import io.github.hubertrybak.codestory.ui.StoryListPanel
import io.github.hubertrybak.codestory.ui.StoryPlayer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant

/** Tells the tool window that the stories changed. */
fun interface StoryListListener {
    /** [shownStoryKey] identifies the repository and story that an agent just showed, if any. */
    fun storiesChanged(shownStoryKey: String?)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<StoryListListener> = Topic(StoryListListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/** The stories of a project: the handler of the agent API and the source of the tool window. */
@Service(Service.Level.PROJECT)
class CodeStoryService(private val project: Project) : Disposable {

    /** Writes moved anchors in the order of the checks. */
    private val anchorWriter = AppExecutorUtil.createBoundedApplicationPoolExecutor("Code Story anchors", 1)

    /** The story that an agent showed last. The tool window selects it when it opens. */
    @Volatile
    var lastShownStoryId: String? = null
        private set

    /** The repository and story that the tool window selects when it opens: shown by an agent, or opened from a notification. */
    @Volatile
    var lastShownStoryKey: String? = null
        private set

    fun storyFolders(): List<StoryFolder> = StoryRepositories(project).folders()

    fun storyFolder(): StoryFolder {
        val basePath = checkNotNull(project.basePath) { "The project has no folder: $project" }
        return StoryFolder.forProject(Path.of(basePath))
    }

    /**
     * Handles `code_story_show`: checks the [story] JSON against the project files and saves it
     * in `.codestory/local/` when it has no errors. A rejected story saves nothing.
     * Reads and writes files, so call it on a background dispatcher.
     */
    suspend fun show(story: JsonElement, storyId: String? = null, repositoryPath: String? = null): ShowReport {
        if (storyId != null && !StoryFolder.isValidId(storyId)) return ShowReport.rejected(listOf(
            ReportItem(null, ErrorCode.INVALID_STORY_ID, "The story ID is not valid.", "Use an ID returned by code_story_list."),
        ))
        val structure = when (val result = StoryValidator(null).validate(story)) {
            is ValidationResult.Invalid -> return ShowReport.rejected(result.errors, corrections = result.corrections)
            is ValidationResult.Valid -> result.story
        }
        val selection = StoryRepositories(project).select(structure, storyId, repositoryPath)
        selection.error?.let { return ShowReport.rejected(listOf(it)) }
        val folder = checkNotNull(selection.folder)
        val checked = when (val result = validateIncomingStory(story, folder)) {
            is ValidationResult.Invalid -> return ShowReport.rejected(result.errors, corrections = result.corrections)
            is ValidationResult.Valid -> result
        }
        // A story saves its base as a commit SHA, so that `HEAD` still names the base after a commit.
        val (story, baseWarnings) = StoryChangeInfo.getInstance(project).resolveBase(folder.root, checked.story)
        val warnings = StoryWritingChecks(CodeStorySettings.getInstance().state.optionalStyleHints).check(story) + baseWarnings
        val git = currentGit(folder.root)
        return when (val saved = folder.save(story.copy(delivery = StoryFormat.MCP_DELIVERY), Instant.now(), git, storyId)) {
            is StoryFolder.SaveResult.TooLarge -> ShowReport.rejected(listOf(ReportItem.storyTooLarge(saved.bytes)), warnings, checked.corrections)
            is StoryFolder.SaveResult.NotFound -> ShowReport.rejected(listOf(
                ReportItem(null, ErrorCode.STORY_NOT_FOUND, "The story `${saved.id}` was not found.", "Call code_story_list to find the current story ID."),
            ), warnings, checked.corrections)
            is StoryFolder.SaveResult.Saved -> {
                storyDelivered(StoredStory(saved.id, folder.root, saved.file, saved.story, Files.getLastModifiedTime(saved.file)), storyId != null)
                ShowReport.accepted(saved.id, folder.relativePath(saved.file), warnings, checked.corrections)
            }
        }
    }

    /** Patch against the latest saved story under the same lock as replies and full replacements. */
    suspend fun patch(storyId: String, operations: List<JsonElement>, repositoryPath: String? = null): ShowReport {
        fun rejected(code: ErrorCode, message: String) = patchReport(ShowReport.rejected(listOf(
            ReportItem(null, code, message, "Read code_story_get for the story and step IDs, then retry code_story_patch."),
        )))
        if (!StoryFolder.isValidId(storyId)) return rejected(ErrorCode.INVALID_STORY_ID, "The story ID is not valid.")
        val matches = storyFolders().filter { repositoryPath == null || it.root.toString() == repositoryPath }.mapNotNull { it.get(storyId) }
        if (matches.size > 1) return rejected(ErrorCode.REPOSITORY_REQUIRED, "That story ID exists in several repositories. Pass repositoryPath from code_story_list.")
        val stored = matches.singleOrNull() ?: return rejected(ErrorCode.STORY_NOT_FOUND, "The story `$storyId` was not found.")
        val folder = StoryFolder(stored.root)
        val live = AnchorTracker.getInstance(project).snapshot()
        val files = readAction { ideFiles(stored.root, live) }
        var report: ShowReport? = null
        val result = folder.update(storyId) { previous ->
            val current = StoryAnchors.resolve(previous, files, live?.takeIf { it.storyKey == stored.key }?.ranges.orEmpty()).story
            val patched = StoryPatch.apply(current, storyId, operations, files)
            if (patched.errors.isNotEmpty()) {
                report = ShowReport.rejected(patched.errors)
                return@update previous
            }
            val unchanged = current.steps.filter { it.id !in patched.updatedIds }.associateBy { checkNotNull(it.id) }
            val checked = when (val validation = StoryValidator(files, unchangedSteps = unchanged).validate(patched.story)) {
                is ValidationResult.Invalid -> {
                    report = ShowReport.rejected(validation.errors, corrections = validation.corrections)
                    return@update previous
                }
                is ValidationResult.Valid -> validation
            }
            StoryRepositories(project).select(checked.story, storyId, stored.root.toString()).error?.let {
                report = ShowReport.rejected(listOf(it))
                return@update previous
            }
            val (validated, baseWarnings) = StoryChangeInfo.getInstance(project).resolveBase(stored.root, checked.story)
            val revised = validated.copy(createdAt = previous.createdAt, git = previous.git, delivery = StoryFormat.MCP_DELIVERY)
            val warnings = StoryWritingChecks(CodeStorySettings.getInstance().state.optionalStyleHints).check(revised) + baseWarnings
            report = ShowReport.accepted(storyId, folder.relativePath(stored.file), warnings, checked.corrections)
            StoryRevisions.record(previous, StoryThreads.carryOver(previous, revised), patched.updatedIds, patched.confirmedIds)
        }
        when (result) {
            StoryFolder.UpdateResult.NotFound -> return rejected(ErrorCode.STORY_NOT_FOUND, "The story `$storyId` was not found.")
            is StoryFolder.UpdateResult.TooLarge -> return patchReport(ShowReport.rejected(
                listOf(ReportItem.storyTooLarge(result.bytes)), report?.warnings.orEmpty(), report?.corrections.orEmpty(),
            ))
            is StoryFolder.UpdateResult.Updated -> {
                report = checkNotNull(report).copy(file = folder.relativePath(result.stored.file))
                storyDelivered(result.stored, replacing = true)
            }
            is StoryFolder.UpdateResult.Unchanged -> if (report?.file != null) report = checkNotNull(report).copy(file = folder.relativePath(result.stored.file))
        }
        return patchReport(checkNotNull(report))
    }

    private fun patchReport(report: ShowReport): ShowReport = report.copy(next = if (report.errors.isNotEmpty())
        "Nothing was saved. Next: fix the errors and retry code_story_patch with the complete operations list."
    else if (report.warnings.isNotEmpty())
        "Next: fix writing warnings once with code_story_patch, then answer fetched comments with code_story_reply."
    else "Next: answer fetched comments with code_story_reply, or call code_story_feedback for pending comments.")

    /** The same arrival validation for MCP and file drops, including unsaved editor text. */
    suspend fun validateIncomingStory(json: JsonElement, folder: StoryFolder = storyFolder()): ValidationResult {
        val structure = when (val result = StoryValidator(null).validate(json)) {
            is ValidationResult.Invalid -> return result
            is ValidationResult.Valid -> result.story
        }
        StoryRepositories(project).select(structure, null, folder.root.toString()).error?.let {
            return ValidationResult.Invalid(listOf(it))
        }
        val (documents, live) = readAction { openDocuments() to AnchorTracker.getInstance(project).snapshot() }
        val files = ideFiles(folder.root, documents.associate { (path, text) -> realPath(path) to text } + live?.texts.orEmpty())
        return StoryValidator(files).validate(json)
    }

    fun storyDelivered(stored: StoredStory, replacing: Boolean) {
        project.service<StoryReaderState>().opened(stored)
        lastShownStoryId = stored.id
        lastShownStoryKey = stored.key
        project.messageBus.syncPublisher(StoryListListener.TOPIC).storiesChanged(stored.key)
        startPlayback(stored, replacing)
    }

    /**
     * The saved stories, newest first, with the code of their steps found again: a story opens.
     * Moved anchors are written back to local stories. Reads files, so call it in the background.
     */
    fun stories(): List<StoredStory> {
        val live = AnchorTracker.getInstance(project).snapshot()
        val reader = project.service<StoryReaderState>()
        val rejected = StoryFileWatcher.getInstance(project).problems.keys
        return storyFolders().flatMap { folder ->
            val stored = folder.list { file, reason -> LOG.warn("Code Story skipped $file: $reason") }.filter { it.file !in rejected }
            val files = ideFiles(folder.root, live)
            stored.map { resolve(folder, it, files, live).withNewMarker(reader.isNew(it)) }
        }.sortedWith(compareByDescending<StoredStory> { it.story.createdAt }.thenByDescending { it.id }.thenBy { it.root.toString() })
    }

    /** Full saved story, including plugin-owned metadata and stable step IDs, with its anchors checked. */
    fun get(storyId: String, repositoryPath: String? = null): JsonObject? {
        val matches = storyFolders().filter { repositoryPath == null || it.root.toString() == repositoryPath }
            .mapNotNull { it.get(storyId) }
        if (matches.size > 1) throw IOException("That story ID exists in several repositories. Pass repositoryPath from code_story_list.")
        val stored = matches.singleOrNull() ?: return null
        val folder = StoryFolder(stored.root)
        val live = AnchorTracker.getInstance(project).snapshot()
        val resolved = resolve(folder, stored, ideFiles(folder.root, live), live)
        return Json.parseToJsonElement(StoryFormat.encode(resolved.story, resolved.id)).jsonObject
    }

    fun list(): List<StoryListEntry> = stories().map(StoryListEntry::of)

    /** File operations run in the background; playback metadata is adjusted on the EDT. */
    fun setShared(stored: StoredStory, shared: Boolean) {
        val moved = StoryFileWatcher.getInstance(project).setShared(stored, shared)
        project.service<StoryReaderState>().opened(moved)
        ApplicationManager.getApplication().invokeLater({
            StoryPlayer.getInstance(project).relocated(moved)
        }, ModalityState.nonModal(), project.disposed)
        project.messageBus.syncPublisher(StoryListListener.TOPIC).storiesChanged(null)
    }

    fun delete(stored: StoredStory) {
        if (!StoryFolder(stored.root).delete(stored)) throw IOException("The story has moved or was already deleted. Refresh the list.")
        project.service<StoryReaderState>().deleteStoryDrafts(stored.key)
        ApplicationManager.getApplication().invokeLater({
            val player = StoryPlayer.getInstance(project)
            if (player.currentStory?.key == stored.key) player.stop()
        }, ModalityState.nonModal(), project.disposed)
        project.messageBus.syncPublisher(StoryListListener.TOPIC).storiesChanged(null)
    }

    fun opened(stored: StoredStory) {
        if (project.service<StoryReaderState>().opened(stored) && stored.isNew) {
            project.messageBus.syncPublisher(StoryListListener.TOPIC).storiesChanged(null)
        }
    }

    /**
     * Writes the reader's drafts of [stored] into its local or shared file as one numbered round.
     * Returns the number of submitted comments. Reads and writes files, so call it in the background.
     */
    fun submitRound(stored: StoredStory): Int {
        val reader = project.service<StoryReaderState>()
        val drafts = reader.drafts(stored.key)
        if (drafts.isEmpty()) return 0
        val result = StoryFolder(stored.root).update(stored.id) { StoryThreads.submit(it, drafts.map(StoryDraft::comment), Instant.now()) }
        val updated = changedStory(result, "The comments make the story file larger than ${StoryLimits.MAX_FILE_BYTES / 1024} KB. Shorten them.")
            ?: return 0
        reader.deleteDrafts(drafts.map { it.id })
        threadsChanged(listOf(updated))
        roundSubmitted(drafts.size)
        return drafts.size
    }

    /**
     * Takes back a comment that no agent fetched. It becomes a draft again. Returns `false` when an
     * agent already fetched it. Call in the background.
     */
    fun withdraw(stored: StoredStory, threadId: String): Boolean {
        var draft: DraftComment? = null
        val result = StoryFolder(stored.root).update(stored.id) { story ->
            StoryThreads.withdraw(story, threadId)?.let { (updated, comment) -> draft = comment; updated } ?: story
        }
        val updated = changedStory(result, null) ?: return false
        draft?.let { project.service<StoryReaderState>().saveDraft(StoryDraft(story = stored.key, step = it.stepId, thread = it.threadId, kind = it.kind, text = it.text)) }
        threadsChanged(listOf(updated))
        return true
    }

    /** Closes a thread that an agent replied to. Returns `false` when the thread waits for an agent. Call in the background. */
    fun resolve(stored: StoredStory, threadId: String): Boolean {
        val result = StoryFolder(stored.root).update(stored.id) { StoryThreads.resolve(it, threadId) ?: it }
        threadsChanged(listOf(changedStory(result, null) ?: return false))
        return true
    }

    /** Adds a refresh item immediately, including when there are no reader drafts. */
    fun requestRefresh(stored: StoredStory, stepId: String? = null): Boolean {
        val result = StoryFolder(stored.root).update(stored.id) { story ->
            StoryThreads.requestRefresh(story, stepId?.takeIf { id -> story.steps.any { it.id == id } }, Instant.now())
        }
        threadsChanged(listOf(changedStory(result, null) ?: return false))
        roundSubmitted(1)
        return true
    }

    /** Finds stories in the requested scope without fetching their comments. Call off the EDT. */
    private fun feedbackStories(storyId: String?, repositoryPath: String?): List<StoredStory>? {
        val rejected = StoryFileWatcher.getInstance(project).problems.keys
        val folders = storyFolders().filter { repositoryPath == null || it.root.toString() == repositoryPath }
        return if (storyId == null) folders.flatMap { folder -> folder.list().filter { it.file !in rejected } }
            else folders.mapNotNull { it.get(storyId) }.also {
                if (it.size > 1) throw IOException("That story ID exists in several repositories. Pass repositoryPath from code_story_list.")
            }.ifEmpty { return null }
    }

    /** Wait off the EDT without holding a thread or changing drafts. Client cancellation propagates. */
    suspend fun listenForFeedback(storyId: String?, repositoryPath: String?, waitSeconds: Int = 25): FeedbackListenReport? {
        require(waitSeconds in 1..25) { "waitSeconds must be between 1 and 25." }
        // Poll saved stories too, so rounds written by another IDE or a shared-file update are detected.
        val result = withTimeoutOrNull(waitSeconds * 1000L) {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (project.isDisposed || !CodeStorySettings.getInstance().state.automaticFeedback) {
                    return@withTimeoutOrNull FeedbackListenReport("stopped", next = "Stop listening. Automatic feedback is disabled or the project is closed. Use code_story_feedback for a manual fetch.")
                }
                val stories = feedbackStories(storyId, repositoryPath)
                    ?: return@withTimeoutOrNull FeedbackListenReport("not-found", next = "Call code_story_list for current story IDs.")
                if (stories.any { StoryThreads.pending(it.story).isNotEmpty() }) {
                    val report = feedback(storyId, repositoryPath)
                    // The reader may withdraw the round between the check and fetch.
                    if (report != null && report.comments.isNotEmpty()) {
                        return@withTimeoutOrNull FeedbackListenReport("feedback", report,
                            "Answer these comments with code_story_reply, then call code_story_listen again. Never change code for a question.")
                    }
                }
                delay(500)
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable")
        }
        return if (result?.status == "not-found") null else result
            ?: FeedbackListenReport("timeout", next = "No submitted feedback yet. Call code_story_listen again unless the developer stopped listening or gave a new task.")
    }

    /** Fetches comments, marking them received until answered. Call off the EDT; null means no such story. */
    fun feedback(storyId: String?, repositoryPath: String?): FeedbackReport? {
        val found = feedbackStories(storyId, repositoryPath) ?: return null
        // The agent gets what this update marked, so a comment that arrives meanwhile waits for the next call.
        val marked = mutableListOf<StoredStory>()
        val live = AnchorTracker.getInstance(project).snapshot()
        val fetched = found.mapNotNull { stored ->
            val files = ideFiles(stored.root, live)
            when (val result = StoryFolder(stored.root).update(stored.id) { story ->
                val resolved = StoryAnchors.resolve(story, files, live?.takeIf { it.storyKey == stored.key }?.ranges.orEmpty())
                StoryThreads.markReceived(story, resolved.story.steps.mapIndexedNotNull { index, step ->
                    step.id?.takeIf { resolved.statuses[index] in listOf(StepStatus.CODE_CHANGED, StepStatus.LOST) }
                })
            }) {
                is StoryFolder.UpdateResult.Updated -> result.stored.also(marked::add)
                is StoryFolder.UpdateResult.Unchanged -> result.stored
                StoryFolder.UpdateResult.NotFound, is StoryFolder.UpdateResult.TooLarge -> null
            }
        }
        threadsChanged(marked)
        val resolved = fetched.map { resolve(StoryFolder(it.root), it, ideFiles(it.root, live), live) }
        return StoryFeedback.report(resolved, requested = storyId != null)
    }

    /**
     * Handles `code_story_reply`: saves each reply that fits its fetched comment, in one file write,
     * and tells the reader that answers arrived. Reads and writes files, so call it in the background.
     */
    fun reply(storyId: String, replies: List<JsonElement>, repositoryPath: String? = null): ReplyReport {
        fun failed(code: ErrorCode, message: String, hint: String) = ReplyReport.failed(ReplyIssue(null, null, code.code, message, hint))
        if (!StoryFolder.isValidId(storyId)) return failed(ErrorCode.INVALID_STORY_ID, "The story ID is not valid.", "Use a storyId from code_story_feedback.")
        if (replies.isEmpty()) return failed(ErrorCode.MISSING_FIELD, "The call has no replies.", "Send one reply for each comment from code_story_feedback.")
        val matches = storyFolders().filter { repositoryPath == null || it.root.toString() == repositoryPath }.mapNotNull { it.get(storyId) }
        if (matches.size > 1) return failed(ErrorCode.REPOSITORY_REQUIRED, "That story ID exists in several repositories.", "Pass repositoryPath from code_story_feedback.")
        val stored = matches.singleOrNull()
            ?: return failed(ErrorCode.STORY_NOT_FOUND, "The story `$storyId` was not found.", "Call code_story_feedback for the current comments and story IDs.")
        val (parsed, inputErrors) = ReplyInput.parse(replies)
        var replied: StoryThreads.ReplyResult? = null
        val live = AnchorTracker.getInstance(project).snapshot()
        val files = ideFiles(stored.root, live)
        val result = StoryFolder(stored.root).update(storyId) { story ->
            val resolved = StoryAnchors.resolve(story, files, live?.takeIf { it.storyKey == stored.key }?.ranges.orEmpty())
            val stale = story.steps.mapIndexedNotNull { index, step ->
                step.id?.takeIf { resolved.statuses[index] in listOf(StepStatus.CODE_CHANGED, StepStatus.LOST) }
            }.toSet()
            StoryThreads.reply(story, parsed, Instant.now(), stale).also { replied = it }.story
        }
        if (result is StoryFolder.UpdateResult.TooLarge) return ReplyReport.of(storyId, emptyList(), (inputErrors + parsed.map { (index, reply) ->
            ReplyIssue(index + 1, reply.threadId, ErrorCode.STORY_TOO_LARGE.code, ReportItem.storyTooLarge(result.bytes).message, "Shorten the replies.")
        }).sortedBy { it.reply }, emptyList())
        if (result == StoryFolder.UpdateResult.NotFound) return failed(ErrorCode.STORY_NOT_FOUND, "The story `$storyId` was not found.", "Call code_story_feedback for the current comments and story IDs.")
        val outcome = checkNotNull(replied)
        val checks = StoryWritingChecks(CodeStorySettings.getInstance().state.optionalStyleHints)
        // The first reply to a thread is the saved one.
        val warnings = parsed.filter { it.value.threadId in outcome.saved }.distinctBy { it.value.threadId }.flatMap { (index, reply) ->
            checks.reply(reply.text, reply.outcome).map { ReplyIssue(index + 1, reply.threadId, it.code, it.message, it.hint) }
        }
        if (result is StoryFolder.UpdateResult.Updated) {
            threadsChanged(listOf(result.stored))
            answersArrived(result.stored, outcome.saved.size)
        }
        return ReplyReport.of(storyId, outcome.saved, (inputErrors + outcome.errors).sortedBy { it.reply }, warnings)
    }

    /** Opens the tool window at [stored]. */
    fun showInToolWindow(stored: StoredStory) {
        lastShownStoryKey = stored.key
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
        project.messageBus.syncPublisher(StoryListListener.TOPIC).storiesChanged(stored.key)
    }

    /** The story after [result], or `null` when nothing changed. A missing or too large file is an error for the reader. */
    private fun changedStory(result: StoryFolder.UpdateResult, tooLarge: String?): StoredStory? = when (result) {
        is StoryFolder.UpdateResult.Updated -> result.stored
        is StoryFolder.UpdateResult.Unchanged -> null
        is StoryFolder.UpdateResult.TooLarge -> throw IOException(tooLarge ?: "The story file is larger than ${StoryLimits.MAX_FILE_BYTES / 1024} KB.")
        StoryFolder.UpdateResult.NotFound -> throw IOException("The story has moved or was deleted. Refresh the list.")
    }

    /** The tool window and the playing story show the new threads; the player stays at its step. */
    private fun threadsChanged(stories: List<StoredStory>) {
        if (stories.isEmpty()) return
        project.messageBus.syncPublisher(StoryListListener.TOPIC).storiesChanged(null)
        ApplicationManager.getApplication().invokeLater({
            stories.forEach { StoryPlayer.getInstance(project).threadsChanged(it) }
        }, ModalityState.nonModal(), project.disposed)
    }

    /** Submitted rounds are available to waiting agents; keep a command for stopped sessions. */
    private fun roundSubmitted(count: Int) = ApplicationManager.getApplication().invokeLater({
        fun copy(command: String) = CopyPasteManager.getInstance().setContents(StringSelection(command))
        val handoff = if (CodeStorySettings.getInstance().state.automaticFeedback)
            "Your listening agent picks up the round automatically. If your agent is stopped, tell it"
        else "Automatic listening is off. Tell your agent"
        NotificationGroupManager.getInstance().getNotificationGroup(StoryListPanel.NOTIFICATION_GROUP)
            .createNotification(
                "Waiting for your agent",
                "${if (count == 1) "Your comment is" else "Your $count comments are"} in the story. $handoff $FEEDBACK_COMMAND " +
                    "($CODEX_FEEDBACK_COMMAND in Codex).",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimple("Copy $FEEDBACK_COMMAND") { copy(FEEDBACK_COMMAND) })
            .addAction(NotificationAction.createSimple("Copy for Codex") { copy(CODEX_FEEDBACK_COMMAND) })
            .notify(project)
    }, ModalityState.nonModal(), project.disposed)

    private fun answersArrived(stored: StoredStory, count: Int) {
        if (count == 0) return
        ApplicationManager.getApplication().invokeLater({
            NotificationGroupManager.getInstance().getNotificationGroup(StoryListPanel.NOTIFICATION_GROUP)
                .createNotification(
                    if (count == 1) "An answer arrived" else "$count answers arrived",
                    StringUtil.escapeXmlEntities("Your agent replied to your comments on “${stored.story.title}”."),
                    NotificationType.INFORMATION,
                )
                .addAction(NotificationAction.createSimpleExpiring("Open") { showInToolWindow(stored) })
                .notify(project)
        }, ModalityState.nonModal(), project.disposed)
    }

    /** The current repository state; unavailable Git fields stay null. Call in the background. */
    fun currentGit(root: Path): StoryGit = StoryGitMetadata.EP.extensionList.firstOrNull()?.snapshot(project, root) ?: StoryGit()

    /**
     * The files under [root] as the IDE shows them: the text of open documents, including unsaved
     * edits, before the text on disk. The documents of [live] markers give the text that their
     * lines refer to.
     */
    fun ideFiles(root: Path, live: LiveSnapshot? = AnchorTracker.getInstance(project).snapshot()): StoryFiles {
        val documents = ApplicationManager.getApplication().runReadAction(Computable { openDocuments() })
        return ideFiles(root, documents.associate { (path, text) -> realPath(path) to text } + live?.texts.orEmpty())
    }

    /**
     * Saves the anchors that a check moved: [resolved] is [stored] after the check. Only local
     * stories change; corrections for shared stories stay in memory. New lines, and steps that
     * become or stop being lost or changed, also refresh the story list. Writes in the background.
     */
    fun anchorsChecked(stored: StoredStory, resolved: StoredStory) {
        // The list shows lost and changed steps only, so it needs no refresh for moved ones.
        fun shown(story: StoredStory, index: Int) = story.status(index).takeIf { it == StepStatus.LOST || it == StepStatus.CODE_CHANGED }
        val unchanged = stored.story.steps.indices.all { shown(stored, it) == shown(resolved, it) }
        if (resolved.story == stored.story && unchanged) return
        anchorWriter.execute {
            if (resolved.story != stored.story) saveAnchors(StoryFolder(stored.root), stored, resolved)
            if (!project.isDisposed) project.messageBus.syncPublisher(StoryListListener.TOPIC).storiesChanged(null)
        }
    }

    private fun resolve(folder: StoryFolder, stored: StoredStory, files: StoryFiles, live: LiveSnapshot?): StoredStory {
        val ranges = live?.takeIf { it.storyKey == stored.key }?.ranges.orEmpty()
        val resolved = stored.resolved(StoryAnchors.resolve(stored.story, files, ranges))
        if (resolved.story != stored.story) saveAnchors(folder, stored, resolved)
        return resolved
    }

    private fun saveAnchors(folder: StoryFolder, stored: StoredStory, resolved: StoredStory) {
        try {
            folder.updateAnchors(stored.id, stored.story, resolved.story)
        } catch (e: IOException) {
            LOG.warn("Code Story cannot save the moved anchors of ${stored.id}", e)
        }
    }

    private fun ideFiles(root: Path, texts: Map<Path, CharSequence>): StoryFiles {
        // Many steps and stories point at the same files.
        val disk = HashMap<Path, CharSequence>()
        return RootStoryFiles(root) { path -> texts[realPath(path)] ?: disk.getOrPut(path) { TextFiles.readDisk(path) } }
    }

    /** Documents with unsaved edits or in an editor: for these files, the IDE can show other text than the disk has. */
    private fun openDocuments(): List<Pair<Path, CharSequence>> {
        val manager = FileDocumentManager.getInstance()
        val documents = (manager.unsavedDocuments.asSequence() + EditorFactory.getInstance().allEditors.asSequence().map { it.document }).distinct()
        return documents.mapNotNull { document ->
            val file = manager.getFile(document) ?: return@mapNotNull null
            file.fileSystem.getNioPath(file)?.let { it to document.immutableCharSequence }
        }.toList()
    }

    private fun realPath(path: Path): Path = AnchorTracker.realPath(path)

    private fun startPlayback(stored: StoredStory, replacing: Boolean) {
        ApplicationManager.getApplication().invokeLater(
            {
                val player = StoryPlayer.getInstance(project)
                if (replacing && player.currentStory?.key == stored.key) player.reload(stored)
                else player.restart(stored)
                ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
                if (CodeStorySettings.getInstance().state.bringToFront) {
                    WindowManager.getInstance().getFrame(project)?.let { IdeFocusManager.getInstance(project).toFront(it.rootPane) }
                }
            },
            ModalityState.nonModal(),
            project.disposed,
        )
    }

    override fun dispose() { anchorWriter.shutdownNow() }

    companion object {
        const val TOOL_WINDOW_ID = "Code Story"
        private val LOG = logger<CodeStoryService>()

        fun getInstance(project: Project): CodeStoryService = project.service()
    }
}
