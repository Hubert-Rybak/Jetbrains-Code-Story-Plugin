package io.github.hubertrybak.codestory.mcp

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpCallInfoOrNull
import com.intellij.mcpserver.noSuitableProjectError
import io.github.hubertrybak.codestory.CodeStoryService
import io.github.hubertrybak.codestory.CodeStoryGuide
import io.github.hubertrybak.codestory.core.FeedbackReport
import io.github.hubertrybak.codestory.core.FeedbackListenReport
import io.github.hubertrybak.codestory.core.ReplyReport
import io.github.hubertrybak.codestory.core.ShowReport
import io.github.hubertrybak.codestory.core.StoryFolder
import io.github.hubertrybak.codestory.core.StoryListReport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.IOException

/** Code Story tools in the IDE's built-in MCP server. Loaded only when the MCP Server plugin is present. */
class CodeStoryToolset : McpToolset {

    @McpTool(name = "code_story_guide")
    @McpDescription(description = "Returns the Markdown story-building and writing guide, format and example for this installed Code Story version. Call this first, before writing or revising a story.")
    fun storyGuide(): String = CodeStoryGuide.markdown()

    @McpTool(name = "code_story_list")
    @McpDescription(description = "Lists all local and shared stories, newest first: ID, title, type, creation date, branch, folder and code status. The status is ok, moved (the IDE found the code at other lines and updated them), code-changed (the code in a range is different from the code that the step explains) or lost (the IDE cannot find the code), with movedSteps, changedSteps and lostSteps. pendingComments counts reader comments that wait for code_story_feedback. Call code_story_get with an ID to read a full story. Always pass projectPath.")
    suspend fun listStories(): StoryListReport = withService {
        StoryListReport(it.list(), "Next: call code_story_get with a story ID to read it before a revision.")
    }

    @McpTool(name = "code_story_get")
    @McpDescription(description = "Returns the full saved story, including creation metadata, baseRef, step IDs, the current lines of each location and the reader's comment threads. rangeText is the code that the step explains, stored by the IDE. Read it before a revision, keep its step IDs and baseRef, and call code_story_show with storyId to replace it. The threads belong to the IDE: do not send them back; they stay with their steps by step ID. Always pass projectPath.")
    suspend fun getStory(
        @McpDescription(description = "An existing story ID returned by code_story_list.")
        storyId: String,
        @McpDescription(description = "Absolute repository root from code_story_list. Required when the ID exists in several repositories.")
        repositoryPath: String? = null,
    ): SavedStoryResult = withService {
        if (!StoryFolder.isValidId(storyId)) throw McpExpectedError("Invalid storyId. Next: call code_story_list for an ID.")
        val saved = it.get(storyId, repositoryPath) ?: throw McpExpectedError("Story '$storyId' was not found. Next: call code_story_list for the current IDs.")
        SavedStoryResult(JsonObject(saved + ("next" to JsonPrimitive("Next: call code_story_patch to edit steps, code_story_show to replace the story, or code_story_feedback for pending comments."))))
    }

    @McpTool(name = "code_story_show")
    @McpDescription(
        description = """
        |Shows a code story in the JetBrains IDE: a short walkthrough of code in ordered steps.
        |Call code_story_guide first for this installed version's writing rules and format.
        |Include the type (change, exploration, bug-trace or review), summary, original request, and author (agent and model).
        |The plugin adds the creation time and Git branch and commit. Each step explains one idea in 1 to 3 short sentences.
        |Only for a story about a change, set baseRef to the SHA of the commit before the change, or HEAD for uncommitted work. The IDE saves the full commit SHA,
        |marks each step "A" (all lines added) or "M" (some lines changed), and shows the diff of a step. A baseRef that Git does not know gives a warning.
        |A step has one small code range, or no location for text-only overview and what-to-check steps.
        |Most steps explain code. Set kind to context for background that the step before it needs, such as a method that it calls,
        |or to proposal for a change that you suggest but did not make. A proposal can set proposedCode: the code for the lines of its location.
        |Keep step IDs stable when revising a story; omit them to let the plugin assign IDs.
        |Use 3 to 12 steps (15 at most). Read every file before you call this tool, so that each path and line number is correct.
        |Paths are relative to the Git repository root. Line numbers start at 1, and a range includes its endLine.
        |With several repositories, pass repositoryPath with the absolute repository root. Every step must stay in that repository.
        |Each location needs anchorText: the exact text of line startLine. The IDE compares it with the code that it shows, including unsaved edits.
        |If the text is a few lines away (20 at most), the IDE moves the range and lists a correction in the report. If it cannot find the text, it rejects the story.
        |If the story has no errors, the IDE saves it in .codestory/local/ and lists it in the Code Story tool window.
        |With an existing storyId, it replaces that story in its current local or shared file and keeps its creation metadata and comment threads.
        |If that story is playing, it reloads at the same step number, or the last step if the new story is shorter.
        |Writing and long-range warnings never block a story and appear only in this report. Fix them once and resend with the returned storyId.
        |If the story has errors, the IDE saves nothing and returns status "rejected" with the errors. Each error, correction and warning has the step number, a code, a message and a hint. Fix all errors, then send the whole story again.
        |Always pass projectPath.
        """,
    )
    suspend fun showStory(
        @McpDescription(description = "The story: title, type, summary, original request, agent and model, and steps in reading order.")
        story: StoryArgument,
        @McpDescription(description = "Omit for a new story. To replace a story in place, use its ID from code_story_list or code_story_show.")
        storyId: String? = null,
        @McpDescription(description = "Absolute repository root within this project. Use it when several repositories have matching paths, for text-only stories, or to disambiguate a storyId.")
        repositoryPath: String? = null,
    ): ShowReport = withService { it.show(story.json, storyId, repositoryPath) }

    @McpTool(name = "code_story_patch")
    @McpDescription(description = "Edits a saved story atomically with ordered operations. Use op update with stepId and title, comment, location (including anchorText; null removes the location), kind or proposedCode; op insert with afterStepId and a complete step; op remove with stepId; or op confirm with stepId to make the current code the new reference. Read code_story_get for stable IDs. All operations pass the same validation as code_story_show and return its report; any error saves nothing. Threads stay on their steps by ID; removed-step threads move to the story with a step removed note. The plugin records story changes for fetched comments and adds the summary to code_story_reply. Always pass projectPath, and repositoryPath when IDs are ambiguous.")
    suspend fun patchStory(
        @McpDescription(description = "The existing story ID from code_story_get or code_story_feedback.")
        storyId: String,
        @McpDescription(description = "Ordered update, insert, remove and confirm operations. Any error rejects the entire patch.")
        operations: List<PatchArgument>,
        @McpDescription(description = "Absolute repository root from code_story_list or code_story_feedback.")
        repositoryPath: String? = null,
    ): ShowReport = withService { it.patch(storyId, operations.map(PatchArgument::json), repositoryPath) }

    @McpTool(name = "code_story_feedback")
    @McpDescription(
        description = """
        |Returns the reader's comments that wait for an agent, oldest round first. Use it for /code-story feedback.
        |The reader posts comments on steps or on the whole story in the IDE. Comments that wait together share a numbered round.
        |Each comment has storyId, round, threadId, kind, target (story or step), the step (ID, number, title, comment, current lines and status),
        |the comment text and the earlier messages of its thread. staleSteps lists steps whose code changed or is lost, including stories without comments when no comments wait.
        |Fetched comments become "received" (the reader sees that an agent works on them) and stay available until an agent replies.
        |Apply change requests with your normal permission prompts, then update or confirm all affected steps with code_story_patch. Refresh stale steps even without comments.
        |Answer every comment with code_story_reply. Never change code for a question. If the explanation was unclear, improve step text or insert steps with code_story_patch before you reply. Always pass projectPath.
        """,
    )
    suspend fun storyFeedback(
        @McpDescription(description = "Optional. A story ID from code_story_list or from the developer. Omit it to get the comments of all stories.")
        storyId: String? = null,
        @McpDescription(description = "Absolute repository root from code_story_list. Required when the ID exists in several repositories.")
        repositoryPath: String? = null,
    ): FeedbackReport = withService {
        if (storyId != null && !StoryFolder.isValidId(storyId)) throw McpExpectedError("Invalid storyId. Next: call code_story_list for an ID.")
        it.feedback(storyId, repositoryPath) ?: throw McpExpectedError("Story '$storyId' was not found. Next: call code_story_list for the current IDs.")
    }

    @McpTool(name = "code_story_listen")
    @McpDescription(description = "Listens for submitted feedback by default after showing a story and after replying. Waits up to 25 seconds without blocking the IDE. Returns status feedback with fetched comments, timeout (call again), or stopped (end listening). Process returned comments with the feedback workflow, then listen again. A posted comment authorizes processing that request; never change code for a question. Stop when the developer cancels or gives a new task. Automatic listening can be disabled in Settings | Tools | Code Story. This requires an active agent tool call; it cannot restart an ended agent turn. Always pass projectPath.")
    suspend fun listenForFeedback(
        @McpDescription(description = "Optional story ID. Omit to listen to all stories in the project.")
        storyId: String? = null,
        @McpDescription(description = "Optional absolute repository root. Required for ambiguous story IDs.")
        repositoryPath: String? = null,
        @McpDescription(description = "Maximum seconds to wait, from 1 to 25. Default 25. On timeout, call again.")
        waitSeconds: Int = 25,
    ): FeedbackListenReport = withService {
        if (storyId != null && !StoryFolder.isValidId(storyId)) throw McpExpectedError("Invalid storyId. Next: call code_story_list for an ID.")
        if (waitSeconds !in 1..25) throw McpExpectedError("waitSeconds must be between 1 and 25.")
        it.listenForFeedback(storyId, repositoryPath, waitSeconds)
            ?: throw McpExpectedError("Story '$storyId' was not found. Next: call code_story_list for the current IDs.")
    }

    @McpTool(name = "code_story_reply")
    @McpDescription(
        description = """
        |Saves your replies to the reader's comments from code_story_feedback. Send one reply per comment, with its threadId and round.
        |Use outcome "answered" for a question, "done" for a change or refresh request, or "declined" with a reason.
        |For done, summarize the changes in text and supply changedFiles as repository-relative paths ([] for a story-only refresh).
        |Done is rejected with stale-steps until every stale step has been updated, replaced or confirmed after feedback was fetched. Later code edits require another review.
        |Answer from the story, the thread and the code, in 1 to 5 short sentences, or one lead sentence and up to 6 bullet items; 100 words at most.
        |A reason to decline has 1 to 3 sentences. Use the Markdown subset of story comments; links to repository files with lines are allowed.
        |Each reply is saved or rejected on its own. The first reply to a comment wins: a reply to a comment that is already answered gets the error already-answered.
        |Writing warnings never block a reply. The reader gets a notification, and the answers appear in the story's threads. Always pass projectPath.
        """,
    )
    suspend fun replyToFeedback(
        @McpDescription(description = "The story ID of the comments, from code_story_feedback.")
        storyId: String,
        @McpDescription(description = "One reply for each comment of the story that you answer.")
        replies: List<ReplyArgument>,
        @McpDescription(description = "Absolute repository root from code_story_feedback. Required when the ID exists in several repositories.")
        repositoryPath: String? = null,
    ): ReplyReport = withService { it.reply(storyId, replies.map(ReplyArgument::json), repositoryPath) }

    private suspend fun <T> withService(action: suspend (CodeStoryService) -> T): T {
        // The MCP server finds the project from the projectPath that it adds to every tool. The plugin never guesses.
        val project = currentCoroutineContext().mcpCallInfoOrNull?.project
            ?: throw noSuitableProjectError("Code Story needs the path of the project that holds the story's files. Next: retry code_story_list with projectPath.")
        return withContext(Dispatchers.IO) {
            try {
                action(CodeStoryService.getInstance(project))
            } catch (e: IOException) {
                throw McpExpectedError("Code Story cannot access the story files: ${e.message}. Next: call code_story_list after correcting the file access problem.")
            }
        }
    }
}
