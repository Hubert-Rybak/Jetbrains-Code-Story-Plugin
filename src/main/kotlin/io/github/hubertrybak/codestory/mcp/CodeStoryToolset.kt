package io.github.hubertrybak.codestory.mcp

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpCallInfoOrNull
import com.intellij.mcpserver.noSuitableProjectError
import io.github.hubertrybak.codestory.CodeStoryService
import io.github.hubertrybak.codestory.CodeStoryGuide
import io.github.hubertrybak.codestory.core.ShowReport
import io.github.hubertrybak.codestory.core.StoryFolder
import io.github.hubertrybak.codestory.core.StoryListReport
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
    @McpDescription(description = "Lists all local and shared stories, newest first: ID, title, type, creation date, branch, folder and code status. The status is ok, moved (the IDE found the code at other lines and updated them), code-changed (the code in a range is different from the code that the step explains) or lost (the IDE cannot find the code), with movedSteps, changedSteps and lostSteps. Call code_story_get with an ID to read a full story. Always pass projectPath.")
    suspend fun listStories(): StoryListReport = withService {
        StoryListReport(it.list(), "Call code_story_get with a story ID to read it before a revision.")
    }

    @McpTool(name = "code_story_get")
    @McpDescription(description = "Returns the full saved story, including creation metadata, step IDs and the current lines of each location. rangeText is the code that the step explains, stored by the IDE. Read it before a revision, keep its step IDs, and call code_story_show with storyId to replace it. Always pass projectPath.")
    suspend fun getStory(
        @McpDescription(description = "An existing story ID returned by code_story_list.")
        storyId: String,
    ): SavedStoryResult = withService {
        if (!StoryFolder.isValidId(storyId)) throw McpExpectedError("Invalid storyId. Use an ID from code_story_list.")
        val saved = it.get(storyId) ?: throw McpExpectedError("Story '$storyId' was not found. Call code_story_list for the current IDs.")
        SavedStoryResult(saved)
    }

    @McpTool(name = "code_story_show")
    @McpDescription(
        description = """
        |Shows a code story in the JetBrains IDE: a short walkthrough of code in ordered steps.
        |Call code_story_guide first for this installed version's writing rules and format.
        |Include the type (change, exploration, bug-trace or review), summary, original request, and author (agent and model).
        |The plugin adds the creation time and Git branch and commit. Each step explains one idea in 1 to 3 short sentences.
        |A step has one small code range, or no location for text-only overview and what-to-check steps.
        |Keep step IDs stable when revising a story; omit them to let the plugin assign IDs.
        |Use 3 to 12 steps (15 at most). Read every file before you call this tool, so that each path and line number is correct.
        |Paths are relative to the Git repository root. Line numbers start at 1, and a range includes its endLine.
        |Each location needs anchorText: the exact text of line startLine. The IDE compares it with the code that it shows, including unsaved edits.
        |If the text is a few lines away (20 at most), the IDE moves the range and lists a correction in the report. If it cannot find the text, it rejects the story.
        |If the story has no errors, the IDE saves it in .codestory/local/ and lists it in the Code Story tool window.
        |With an existing storyId, it replaces that story in its current local or shared file and keeps its creation metadata.
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
    ): ShowReport = withService { it.show(story.json, storyId) }

    private suspend fun <T> withService(action: suspend (CodeStoryService) -> T): T {
        // The MCP server finds the project from the projectPath that it adds to every tool. The plugin never guesses.
        val project = currentCoroutineContext().mcpCallInfoOrNull?.project
            ?: throw noSuitableProjectError("Code Story needs the path of the project that holds the story's files.")
        return withContext(Dispatchers.IO) {
            try {
                action(CodeStoryService.getInstance(project))
            } catch (e: IOException) {
                throw McpExpectedError("Code Story cannot access the story files: ${e.message}")
            }
        }
    }
}
