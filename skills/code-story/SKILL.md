---
name: code-story
description: Builds or revises a short, clickable code story in the developer's JetBrains IDE, and answers the reader's comments on it. Use for /code-story <request> ($code-story in Codex), /code-story feedback, guided walkthroughs, code tours, bug traces and review handoffs.
---

# Code Story

1. If available, call `code_story_guide` first and follow the guide returned by the installed plugin. If the Code Story tools are missing, read the bundled [guide](references/guide.md) and use the file-drop workflow below.
2. Use the conversation to find the subject. Explore when needed, and read every file before using its path and line numbers.
3. For a revision, call `code_story_list`, then `code_story_get(storyId)`. Keep the saved step IDs, so that the reader's comment threads stay on their steps.
4. Build the story and call `code_story_show`. For each location, copy the exact text of line `startLine` into `anchorText`. For a story about a change, set `baseRef` to the commit before the change (`HEAD` for uncommitted work). Pass `projectPath`, and pass the existing `storyId` for a revision.
5. Read `errors`, `corrections`, `warnings` and `next`. Fix errors and warnings once and send the whole story again with the corrected lines. If the first call was accepted, use its returned `storyId` to replace it.
6. Tell the developer the accepted story's title and step count, and point to the Code Story tool window. Explain any remaining rejection.

## Feedback mode

Use it for `/code-story feedback [storyId]` (`$code-story feedback` in Codex). The reader wrote comments on a story in the IDE and submitted them as a round.

1. Call `code_story_feedback` with `projectPath`, and with `storyId` when the developer names a story. It returns the comments that wait for an agent, oldest round first, each with its story, step, thread history, `threadId` and `round`.
2. Answer each question from the story, its thread and the code. Read the code before you answer. Never change code for a question. If the code needs a change, say so in the answer and suggest that the developer asks for it as a change request.
3. If a question shows that the story was unclear, call `code_story_get` for stable step IDs, then use `code_story_patch` to improve step text or insert an explanation. Read its report and fix errors before replying. These edits change the story only, never the code. The plugin adds a “Story changes” line to the reply; do not write this line yourself.
4. For each story, call `code_story_reply` with `storyId` and one reply per comment: its `threadId` and `round`, the outcome `answered`, or `declined` with the reason, and your text. Follow the answer rules in the guide.
5. Read `errors`, `warnings` and `next`. Fix rejected replies and send only those again. Do not reply again to a comment that is already answered.
6. Summarize in the chat what you answered and which story steps you improved, and point the developer to the Code Story tool window for the answers.

Feedback needs the Code Story tools. If they are missing, tell the developer to switch on Settings | Tools | MCP Server.

## File-drop fallback

If the Code Story tools are missing, or the MCP server reports that the target project is not open, write the story file yourself. Do not stop at setup instructions or choose a different open project. A validation rejection from `code_story_show` is not a reason to fall back: fix its reported errors instead.

1. Read the [guide](references/guide.md), [JSON Schema](references/story-v1.schema.json) and [complete file example](references/example.story.json). Use the same writing and location rules as for MCP. Read every referenced file and copy the exact first line into `anchorText`.
2. Find the nearest Git repository root above the target project (a `.git` file also counts); without Git, use the project directory. Create `.codestory/local/` there. If `.codestory/local/.gitignore` is missing, write `*` followed by a newline to keep local stories out of Git.
3. Build a complete schema-version-1 file, including `schemaVersion: 1`, `id`, an ISO 8601 UTC `createdAt`, `git` with `branch` and `commit` (null if unavailable), and a unique, stable `id` for each step. Use your actual agent and model names. Omit `delivery`; the MCP marker would prevent file-drop playback. Omit `rangeText`; the plugin captures the current code on arrival. For change stories, use the commit before the change as `baseRef`, or `HEAD` for uncommitted work.
4. Name a new file `<yyyy-MM-dd>-<lowercase-ascii-slug>.story.json`, using the UTC date and the title. Its `id` is the filename without `.story.json`. Check both `.codestory/local/` and `.codestory/shared/` for collisions; add `-2`, `-3`, and so on rather than replacing another story. For an explicit revision, read the existing file, retain its ID, creation metadata, step IDs and `threads` (the reader's comments, unchanged), and replace its local file. Do not edit a shared story through the local fallback.
5. Serialize UTF-8 JSON with indentation and a final newline; keep the complete file at most 256 KB. Write a uniquely named temporary file in the same local directory with a `.tmp` suffix, then atomically rename it to the final `.story.json` name. Never write the final filename in place. Remove the temporary file if writing fails. For example, in Python use `tempfile.NamedTemporaryFile(dir=local, suffix=".tmp", delete=False)` followed by `os.replace(temp, target)` after closing the file. For a new story, recheck the target is unused before the rename.
6. Tell the developer the title, step count and saved path. The plugin validates the file and shows errors in the Code Story tool window; do not claim IDE acceptance without a report. Valid drops play in every IDE with the project open, or when the project next opens. If needed, explain that Settings | Tools | MCP Server enables direct delivery and validation reports, but file drops already work without it.

The skill version is in [version.txt](version.txt). Install or update it from **Tools | Code Story | Install Code Story skill**. The default installs both `~/.agents/skills/code-story/` and `~/.claude/skills/code-story/`; **Settings | Tools | Code Story** can select project-level installation instead.
