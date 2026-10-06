package io.github.hubertrybak.codestory.mcp

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpCallInfoOrNull
import com.intellij.mcpserver.noSuitableProjectError
import io.github.hubertrybak.codestory.CodeStoryService
import io.github.hubertrybak.codestory.core.ShowReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.IOException

/** Code Story tools in the IDE's built-in MCP server. Loaded only when the MCP Server plugin is present. */
class CodeStoryToolset : McpToolset {

    @McpTool(name = "code_story_show")
    @McpDescription(
        description = """
        |Shows a code story in the JetBrains IDE: a short walkthrough of code in ordered steps.
        |Include the type (change, exploration, bug-trace or review), summary, original request, and author (agent and model).
        |The plugin adds the creation time and Git branch and commit. Each step explains one idea in 1 to 3 short sentences.
        |A step has one small code range, or no location for text-only overview and what-to-check steps.
        |Keep step IDs stable when revising a story; omit them to let the plugin assign IDs.
        |Use 3 to 12 steps (15 at most). Read every file before you call this tool, so that each path and line number is correct.
        |Paths are relative to the Git repository root. Line numbers start at 1, and a range includes its endLine.
        |If the story has no errors, the IDE saves it in .codestory/local/ and lists it in the Code Story tool window.
        |If the story has errors, the IDE saves nothing and returns status "rejected" with the errors. Each error has the step number, a code, a message and a hint. Fix all errors, then send the whole story again.
        |Always pass projectPath.
        """,
    )
    suspend fun showStory(
        @McpDescription(description = "The story: title, type, summary, original request, agent and model, and steps in reading order.")
        story: StoryArgument,
    ): ShowReport {
        // The MCP server finds the project from the projectPath that it adds to every tool. The plugin never guesses.
        val project = currentCoroutineContext().mcpCallInfoOrNull?.project
            ?: throw noSuitableProjectError("Code Story needs the path of the project that holds the story's files.")
        return withContext(Dispatchers.IO) {
            try {
                CodeStoryService.getInstance(project).show(story.json)
            } catch (e: IOException) {
                throw McpExpectedError("Code Story cannot save the story: ${e.message}")
            }
        }
    }
}
