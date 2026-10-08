package io.github.hubertrybak.codestory.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.util.asTools
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.components.service
import io.github.hubertrybak.codestory.McpSetupStatus
import io.github.hubertrybak.codestory.StoryMcpSupport

class CodeStoryMcpSupport : StoryMcpSupport {
    override fun status() = McpSetupStatus(
        available = true,
        enabled = service<McpServerService>().isRunning,
        tools = McpToolset.EP.extensionList.filterIsInstance<CodeStoryToolset>()
            .flatMap { it.asTools() }.map { it.descriptor.name }.sorted(),
    )
}
