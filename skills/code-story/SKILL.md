---
name: code-story
description: Builds or revises a short, clickable code story in the developer's JetBrains IDE. Use for /code-story <request> ($code-story in Codex), guided walkthroughs, code tours, bug traces and review handoffs.
---

# Code Story

1. Call `code_story_guide` first. Follow the guide returned by the installed plugin.
2. Use the conversation to find the subject. Explore when needed, and read every file before using its path and line numbers.
3. For a revision, call `code_story_list`, then `code_story_get(storyId)`. Keep the saved step IDs.
4. Build the story and call `code_story_show`. For each location, copy the exact text of line `startLine` into `anchorText`. Pass `projectPath`, and pass the existing `storyId` for a revision.
5. Read `errors`, `corrections`, `warnings` and `next`. Fix errors and warnings once and send the whole story again with the corrected lines. If the first call was accepted, use its returned `storyId` to replace it.
6. Tell the developer the accepted story's title and step count, and point to the Code Story tool window. Explain any remaining rejection.

If the tools are unavailable or the project is not open, ask the developer to open the project and enable Settings | Tools | MCP Server.
The bundled [guide](references/guide.md) and [JSON Schema](references/story-v1.schema.json) describe the format for offline reference.
