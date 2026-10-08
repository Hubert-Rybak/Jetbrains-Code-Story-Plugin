package io.github.hubertrybak.codestory

import com.intellij.openapi.extensions.ExtensionPointName

/** Loaded only with MCP Server; file-drop and setup UI never link against that optional plugin. */
interface StoryMcpSupport {
    fun status(): McpSetupStatus

    companion object {
        val EP: ExtensionPointName<StoryMcpSupport> = ExtensionPointName.create("io.github.hubertrybak.codestory.mcpSupport")
        fun status(): McpSetupStatus = EP.extensionList.firstOrNull()?.status() ?: McpSetupStatus(false, false, emptyList())
    }
}

data class McpSetupStatus(val available: Boolean, val enabled: Boolean, val tools: List<String>)
