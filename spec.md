# Code Story — Specification (v1)

Status: ready for implementation · Date: 2026-10-05 · Updated: 2026-10-06 (floating step card) · Design record: [`brainstorm.md`](brainstorm.md)

## Problem Statement

Developers who work with AI coding agents (Claude Code, OpenAI Codex CLI, GitHub Copilot) get explanations as chat text. To understand a change made for a ticket, a flow in existing code, or the path of a bug, they must find and open the files and lines that the chat mentions by hand, and they must read long, wordy text. Reviewers who receive an AI-assisted change have even less context: a diff and, at best, a summary.

JetBrains IDEs, and Rider in particular, have no good answer to this:

- The VS Code extension CodeTour defined guided code tours, but it is in maintenance mode, its JetBrains ports are abandoned, and nothing creates tours live from an agent.
- Newer JetBrains plugins (Walkthrough, Marginalis) let agents push steps or comment threads into the IDE. They show steps in floating popups or as review conversations, lose their place when code moves, have no sharing model, and have no rules for clear, short wording. None of them is tested on Rider.

The developer needs a guided, clickable, step-by-step explanation of code that an agent creates on request; that appears in the running IDE next to the code it explains; that stays correct while the code changes; that can be shared with reviewers; and that is written in short, simple English.

## Solution

Code Story is a JetBrains IDE plugin (Rider first; all IntelliJ-based IDEs from 2025.3) plus an agent skill named `code-story`.

1. The developer types `/code-story "<request>"` in the agent (`$code-story` in Codex), for example `/code-story "Explain the main functionality change for this ticket"`.
2. The agent uses the conversation context, and explores the code when the conversation does not cover the subject. It writes a **story**: a title, a short summary and 3–12 ordered **steps**. Each step points at a small code range and has a short comment in ASD-STE100 Simplified Technical English.
3. The agent sends the story to the running IDE through the IDE's built-in MCP server. The plugin checks the story and returns a report (errors, corrections, warnings), so the agent can fix problems at once.
4. The plugin saves the story in `.codestory/local/`, opens the **Code Story** tool window and plays step 1: the file opens, the range is highlighted, and a floating, non-modal card points to the code and shows "Step 1 of 8", the step title, the comment and the Prev / Next / Stop buttons. The card preserves code layout and leaves the editor usable.
5. The developer moves through the steps with the card buttons, the tool window or shortcuts. Anchors follow code edits. Stories about a change show which lines were added or modified, and "Show diff" opens the old and the new code side by side.
6. To hand a change to a reviewer, the developer shares the story (it moves to `.codestory/shared/`) and commits it with the branch. The reviewer plays it after checkout. "Copy as Markdown" gives a text version for the pull request description.
7. If the MCP connection is not available, the agent writes the story file into `.codestory/local/` directly, and the plugin picks it up.
8. While reading, the developer writes comments on steps or on the whole story, each marked as a Question or a Change request, and submits them as one **round**. The developer then tells the agent `/code-story feedback`. The agent fetches the round, answers questions in threads inside the story, applies change requests to the code, and updates every step whose code changed. Steps whose code changed show "code changed" until the agent updates or confirms them, so the story always matches the code.

## User Stories

### Requesting a story

1. As a developer, I want to type `/code-story "<request>"` in my AI coding agent, so that I get a guided walkthrough of the code that answers my request.
2. As a developer, I want the agent to use the context of our current conversation, such as the change we just made, so that I do not have to repeat the ticket or the scope.
3. As a developer, I want the agent to explore the code itself when the conversation does not cover the subject, so that I can ask about any part of the codebase ("How does the payment flow work?").
4. As a developer, I want to ask for a bug trace ("How can this exception occur?"), so that I see the path from the trigger to the failure.
5. As a developer preparing a review, I want to ask for a review handoff story, so that a reviewer sees the change in a logical order together with the risks to check.
6. As a developer using Codex CLI, I want `$code-story <request>` to work the same way, so that I can use my preferred agent.
7. As a developer using GitHub Copilot in Rider or Copilot CLI, I want `/code-story <request>` to work, so that I can use Copilot.
8. As a developer, I want the agent to ask the plugin for the current writing guide before it writes, so that every story follows the rules of the installed plugin version.

### Story content and quality

9. As a developer, I want each story to have 3–12 steps (at most 15), so that the story stays focused.
10. As a developer, I want each step to cover one idea and a small code range (usually 30 lines or fewer), so that I see exactly the important lines.
11. As a developer, I want change stories ordered from the entry point through the core logic, data, side effects, and tests and configuration, so that I follow the change the way it runs.
12. As a developer, I want exploration stories to follow the runtime flow one hop per step, so that I understand how a request moves through the layers.
13. As a developer, I want bug-trace stories ordered from trigger through path to failure point (and fix, if known), so that I understand the cause.
14. As a reviewer, I want review handoff stories to include the risks and edge cases to check, so that I know where to look closely.
15. As a developer, I want the agent to skip mechanical changes (renames, formatting, generated code, lock files, imports) and mention them in one summary sentence at most, so that the story shows what matters.
16. As a developer, I want story comments in ASD-STE100 Simplified Technical English with short sentences, so that I understand each step fast.
17. As a developer, I want titles of 8 words or fewer, a summary of 1–3 sentences and comments of 1–3 sentences (or a lead sentence and up to 4 list items, 60 words at most), so that the card stays small.
18. As a developer, I want comments to support inline code, bold, bullet lists and safe links, so that identifiers and short lists read well.
19. As a developer, I want text-only steps, such as an overview at the start or "what to check" at the end, so that the story can introduce and close the subject without pointing at code.
20. As a developer, I want every story to show its type (change, exploration, bug trace, review), so that I know what kind of explanation I am reading.

### Agent API and validation

21. As an AI agent, I want a `code_story_guide` tool that returns the writing guide, the format and an example for the installed plugin version, so that I always follow the current rules.
22. As an AI agent, I want a `code_story_show` tool with typed parameters, so that I can send a story without writing JSON inside a string.
23. As an AI agent, I want an immediate report with errors, corrections and warnings, each with the step number and a fix hint, so that I can fix the story in my next call.
24. As an AI agent, I want the whole story rejected when any step has an error, so that the developer never sees a half-broken story.
25. As an AI agent, I want the plugin to correct a small line shift by means of my anchor text and tell me about it, so that a slightly stale line number does not fail the story.
26. As an AI agent, I want a clear error when a file does not exist, is outside the project, or when lines are outside the file, so that I can fix the location.
27. As an AI agent, I want `code_story_list` and `code_story_get`, so that I can find and revise an existing story.
28. As an AI agent, I want to send a story with an existing `storyId` to replace it, so that a revision updates the same story in place.
29. As an AI agent, I want to set a `baseRef` commit for change stories, so that the plugin can show added and modified lines and diffs.
30. As an AI agent, I want warnings for text-rule problems (sentence length, word count, semicolons, contractions), so that I can bring the comments closer to ASD-STE100 in one fix round.
31. As an AI agent, I want to name the target project path, so that the story goes to the right project when several projects are open.
32. As an AI agent without MCP access, I want to write the story file into `.codestory/local/` directly, so that the developer still gets the story.

### Playback and navigation

33. As a developer, I want a story that I requested to open in my running IDE within seconds and start at step 1, so that I can read it right after I ask for it.
34. As a developer, I want the IDE window to come to the front when my story arrives, so that I do not have to look for it.
35. As a developer, I want to turn off "bring the IDE to the front", so that a new story does not interrupt my work.
36. As a developer, I want each step to open the file at the right lines, scroll them into the upper third of the editor and highlight them, so that I see exactly the code the step talks about.
37. As a developer, I want steps to open in the preview tab when my IDE uses preview tabs, so that a long story does not fill my tab bar.
38. As a developer, I want keyboard shortcuts for Next Step, Previous Step and Stop Story, so that I can navigate fast.
39. As a developer, I want to change these shortcuts in the Keymap, so that they fit my keymap (Visual Studio, ReSharper or IntelliJ).
40. As a developer, I want the last step to offer "Finish", so that the story ends cleanly.
41. As a developer, I want Play to continue at the step where I stopped and Restart to begin again, so that I can pause and come back later.
42. As a developer, I want only one story to play at a time per project, so that cards, highlights and badges from different stories do not mix.
43. As a developer, I want to edit code while a story plays, so that I can fix things I notice during the walkthrough.
44. As a developer, I want a story that the agent updates while I read it to reload and stay at my step, so that revisions do not throw me back to the start.
45. As a developer, I want ✓ marks on the steps that I visited in this session, so that I see my progress.

### Floating card and editor

46. As a developer, I want a floating, non-modal card beside the highlighted code with a pointer to the range and the step comment, so that I read the explanation and the code together without adding space between code lines or blocking the editor.
47. As a developer, I want the card to show "Step N of M" and the step title, so that I know where I am in the story.
48. As a developer, I want Prev, Next and Stop buttons in the card, so that I can move through the story without leaving the editor.
49. As a developer, I want the gutter to show the number of the current step, so that I can find the step range at a glance.
50. As a developer, I want small numbered gutter badges for the other steps in the same file, so that I can jump between nearby steps with one click.
51. As a developer, I want Stop and Finish to remove the card, the highlight and the badges, so that my editor goes back to normal.
52. As a developer, I want the card, the highlight and the badges to follow my IDE theme (light or dark), so that the UI looks native.

### Tool window

53. As a developer, I want a Code Story tool window that I can dock left, right or bottom, or float, so that it fits my layout.
54. As a developer, I want the tool window to list my stories in two groups, Local and Shared, newest first, so that I find the right story fast.
55. As a developer, I want each story row to show the title, date, branch and a type icon, so that I recognize stories at a glance.
56. As a developer, I want each step row to show its number, title and file and line, so that I can jump to any step directly.
57. As a developer, I want a details pane with the summary, the original request, the agent and model, the date, the branch and the diff base, so that I know what the story answers and who wrote it.
58. As a developer, I want a clear "Written by <agent> (<model>)" label on every story, so that I always know the text is AI-generated.
59. As a developer, I want text-only steps shown in the details pane while the editor stays as it is, so that overview text does not move my editor.
60. As a developer with several repositories in one project, I want stories grouped by repository, so that each repository keeps its own stories.

### Change information

61. As a developer, I want each step of a change story to show whether its lines were added or modified, so that I can tell new code from existing code.
62. As a developer, I want "Show diff" on a step, so that I can see the old and the new code side by side at that range.
63. As a developer, I want exploration and bug-trace stories to show no change information, so that unrelated diffs do not confuse me.

### Code drift and stale stories

64. As a developer, I want anchors to follow my edits while a file is open, so that the card stays at the right code after I change lines above it.
65. As a developer, I want the plugin to find a step's code again after it moved (nearby first, then in the whole file), so that stories survive refactoring and branch switches.
66. As a developer, I want a "lost" warning in the card when the code of a step cannot be found, so that I know the step can be wrong.
67. As a developer, I want the story list to show how many steps are lost or changed, so that I can decide to refresh or delete the story.
68. As a developer, I want the story header to show the branch and commit where the story was made, and a note when I am on another branch, so that I understand why steps can be off.
69. As a developer, I want "Request refresh" to add a refresh item to the story's next feedback round, so that the agent updates stale steps through the same path as my comments.
70. As a developer, I want moved anchors saved for my local stories but not written into shared stories, so that the plugin never creates unexpected Git changes.

### Sharing and story management

71. As a developer, I want new stories saved in a local folder that Git ignores automatically, so that pull requests contain no story files unless I share them.
72. As a developer, I want to share a story with one action that moves it to `.codestory/shared/`, so that I can commit it with my branch for reviewers.
73. As a developer, I want to unshare a story, so that it goes back to my local, Git-ignored folder.
74. As a reviewer, I want shared stories to appear in my IDE when I check out the pull request branch, so that I can walk through the change the author explains.
75. As a reviewer, I want shared stories that arrive through Git to wait in the list, marked as new, without auto-play, so that a checkout does not interrupt me.
76. As a developer, I want to delete a story after a confirmation, so that I can clean up old stories.
77. As a developer, I want "Copy as Markdown" (summary and steps with repository-relative file links and line ranges), so that I can paste the story into a pull request description for reviewers who do not have the plugin.
78. As a developer, I want my questions about a step to go through the feedback loop (see "Reader feedback loop"), so that answers are stored in the story instead of being lost in a chat.
79. As a developer, I want stories stored at the Git repository root with repository-relative paths, so that every solution in the repository sees the same stories and paths match Git on every machine.
80. As a developer running two JetBrains IDEs on the same repository, I want a story delivered through MCP to play only in the IDE that received it, so that both IDEs do not jump at once.
81. As a developer, I want my reading position kept in my IDE and never written into story files, so that shared files contain no personal state.

### Setup and onboarding

82. As a developer, I want to install the `code-story` skill from the IDE with one action (user level by default, project level as an option), so that I do not copy files by hand.
83. As a developer, I want the skill written to both shared skill folders (`.agents/skills/` and `.claude/skills/`), so that all supported agents find it.
84. As a developer, I want the plugin to offer an update of the installed skill after a plugin update, so that the skill matches the plugin.
85. As a developer, I want a notification that explains how to switch on the IDE's MCP server, with a button that opens its settings page, so that the live connection works.
86. As a developer, I want the plugin never to change the MCP server setting by itself, so that I keep control over local access to my IDE.
87. As a developer, I want a "Check setup" action that shows the MCP server state, the registered Code Story tools and the installed skill (location and version), so that I can fix setup problems myself.
88. As a developer, I want one first-run notification with the two setup steps (install the skill, switch on the MCP server), so that I am ready in a minute.
89. As a developer, I want a settings page (Tools | Code Story), so that I can control bring-to-front, optional style hints, the skill install location and the highlight color.

### Compatibility, security and privacy

90. As a developer, I want the plugin to work in Rider and IntelliJ IDEA (and other JetBrains IDEs) from version 2025.3, so that my whole team can use it.
91. As a developer, I want the plugin to work without the MCP server plugin (file drop only) and without the Git plugin (no change information), so that a missing optional part does not break it.
92. As a developer, I want the plugin to make no network calls and send no telemetry, so that my code stays private.
93. As a developer, I want story files from Git treated as untrusted input (no HTML, no commands, no paths outside the project, size limits), so that a shared story cannot harm my machine.
94. As a developer, I want Code Story tools to only create, read and update stories and their threads, so that a local program that calls the IDE's MCP server cannot run code through them.

### Maintenance and distribution

95. As a plugin maintainer, I want the story format defined by a published JSON Schema with a schema version, so that agents and future modes can change safely.
96. As a plugin maintainer, I want the core logic (format, validation, anchoring, text checks) testable without the IDE, so that tests run fast.
97. As a plugin maintainer, I want CI to run the Plugin Verifier against Rider and IntelliJ IDEA from 2025.3 to the current early-access build, so that compatibility breaks show up early.
98. As a plugin maintainer, I want tagged releases to publish the plugin zip to GitHub Releases, so that users can install it from disk before a Marketplace release.

### Reader feedback loop

99. As a developer, I want to write a comment on a step, so that I can ask about that code or ask for a change right where I read it.
100. As a developer, I want to write a comment on the whole story, so that I can ask for missing steps or general changes.
101. As a developer, I want to mark each comment as a Question or a Change request, so that the agent knows whether it may change code.
102. As a developer, I want my comments to stay drafts that I can edit or delete until I submit them, so that I can collect several comments first.
103. As a developer, I want to submit all drafts of a story as one round, so that the agent handles my feedback together.
104. As a developer, I want to withdraw a submitted comment until the agent fetches it, so that I can take back a comment I no longer need.
105. As a developer, I want the IDE to tell me how to hand the round to my agent (`/code-story feedback`, with a copy button), so that I do not have to remember the command.
106. As a developer, I want to see when the agent has received my comments and is working on them, so that I know my feedback arrived.
107. As a developer, I want answers to my questions written as thread replies inside the story, so that I can read them next to the step they explain.
108. As a developer, I want the agent to improve a step text or add steps when my question shows that the story was unclear, so that the story gets better for the next reader.
109. As a developer, I want the agent to apply my change requests to the code and reply "done" with a short summary, so that I can review the result in the story.
110. As a developer, I want every reply to list the story changes it caused (steps updated, added, removed or confirmed), so that I can see how the story changed.
111. As a developer, I want the agent to decline a request with a reason when it cannot or should not do it, so that I understand why nothing changed.
112. As a developer, I want the agent never to change code for a Question, so that I get no code changes that I did not ask for.
113. As a developer, I want steps whose code changed (by the agent or by me) marked "code changed" until the agent updates or confirms them, so that I never read a comment that no longer fits the code.
114. As a developer, I want the plugin to reject a "done" reply while affected steps are not updated or confirmed, so that change requests always leave the story in sync with the code.
115. As a developer, I want a notification when answers arrive and unread markers on steps with new replies, so that I find new answers fast.
116. As a developer, I want to reply in a thread to continue the discussion, and to resolve threads when I am done, so that only open topics stay visible.
117. As a developer, I want threads stored in the story file, so that a reviewer sees the earlier questions and answers when the story is shared.
118. As a developer, I want any agent session in the project to process my feedback, so that feedback is not lost when the session that wrote the story has ended.
119. As a developer, I want optional hook snippets for my agent that mention pending feedback at session start or with my next prompt, so that I do not forget to hand it over.
120. As an AI agent, I want `code_story_feedback` to return the pending comments with their step, location, status and thread history, plus the steps whose code changed or was lost, so that I have all the context for my answers.
121. As an AI agent, I want `code_story_patch` to update, insert, remove or confirm single steps, so that I can change a story without resending all of it.
122. As an AI agent, I want `code_story_reply` to tell me exactly which steps still need an update before "done" is accepted, so that I can finish the round correctly.
123. As an AI agent, I want the threads kept when I replace a story with stable step IDs, so that a story rewrite does not lose the discussion.

## Implementation Decisions

### Platform and packaging

- One single-module IntelliJ Platform plugin written in Kotlin, built with the IntelliJ Platform Gradle Plugin 2.x from the official plugin template.
- Name "Code Story", plugin ID `io.github.hubertrybak.codestory`, license Apache-2.0. Code reused from Walkthrough (MIT) and Marginalis (Apache-2.0) keeps its license notices in a third-party notices file.
- Compatibility: since build 253 (2025.3), no until-build. Compile against 2025.3 with Java 21. Rider is the primary test target. The plugin works in all IntelliJ-based IDEs.
- Required dependency: the base platform module only. Optional dependencies: the bundled MCP Server plugin (without it, only the file drop works) and the Git plugin (without it, no change information).
- No ReSharper (.NET) part. Steps anchor by file, line range and text, never by C# symbol.
- Local IDE only. Remote development and split mode (JetBrains Client) are not supported in v1, so one module is enough.
- Distribution: zip from GitHub Releases ("Install Plugin from Disk"). The JetBrains Marketplace comes later, after a name check.

### Logical modules

1. **Story core** (pure Kotlin, no IDE dependencies): the story model, JSON reading and writing with the schema version, validation rules, the anchor-matching policy, the text checks, Markdown-subset parsing and sanitizing, and the report builder. Input: a story and a provider of file text. Output: a normalized story and a report. This is the fast test seam.
2. **Story repository**: finds the story root (Git root, project folder as fallback), manages `.codestory/local/` (with its own self-ignoring `.gitignore`) and `.codestory/shared/`, assigns story IDs, writes files atomically (temp file, then rename), moves stories between folders (ID kept), deletes stories, and keeps reader state (last step per story, comment drafts, unread markers) in IDE workspace storage.
3. **Agent API (MCP toolset)**: the seven tools below (guide, show, list, get, feedback, patch, reply), registered through the MCP server plugin's toolset extension point. Typed parameters come from Kotlin data classes (no JSON inside strings). The project is chosen through the project path that the MCP server injects; the plugin never guesses.
4. **Folder watcher**: listens to file-system events for `.codestory/` only. New or changed story files are loaded and validated; auto-play follows the rules below.
5. **Anchor tracker**: resolves anchors when a story opens or plays, keeps live range markers while documents are open, and computes the status of each step (ok, moved, code changed, lost).
6. **Change info** (needs the Git plugin): for stories with a `baseRef`, computes the added/modified status per step and opens the IDE diff viewer (base revision against the working tree) at the step range.
7. **Player**: holds the one active story per project, runs step navigation, opens files, scrolls, and drives the step presentation. Card buttons, tool-window actions, gutter clicks and keyboard shortcuts call the same player.
8. **Step presentation**: a floating, non-modal card pointing to the code range, the range highlight, the gutter number for the current step and small gutter badges for other steps in the same file. The card uses the IntelliJ Platform balloon API (`JBPopupFactory.createBalloonBuilder`) with real Swing controls; it never adds code-line inlays. When there is no room beside the code, the card floats below or above the range, or over the code at the editor's right edge; only an editor narrower than the card shows the same card panel in the tool window. Placement and dismissal follow the rules below. Colors follow the IDE theme.
9. **Tool window "Code Story"** (default anchor: right): toolbar, story tree and details pane, as described below.
10. **Setup and onboarding**: the bundled skill, skill install and update, the MCP server check, "Check setup", the first-run notification and the settings page.
11. **Feedback loop**: comment drafts (kept in IDE storage), rounds and threads (kept in the story file), the pending-feedback index for agent hooks, the feedback, patch and reply tools, and the "code changed" check that keeps steps in sync with the code.

### Story format (JSON, own schema, `schemaVersion` 1)

- One file per story, named `<yyyy-MM-dd>-<slug>.story.json`, pretty-printed with a fixed field order (readable diffs), at most 256 KB. The JSON Schema is published in the repository and bundled with the skill.
- Story fields:

  | Field | Set by | Notes |
  |---|---|---|
  | `schemaVersion` | plugin | `1` |
  | `id` | plugin | file name without extension; a number suffix when the name exists; kept on share/unshare |
  | `title` | agent | 8 words at most |
  | `type` | agent | `change`, `exploration`, `bug-trace` or `review` |
  | `summary` | agent | 1–3 sentences |
  | `request` | agent | the original `/code-story` request text |
  | `author` | agent | agent name and model |
  | `createdAt` | plugin | ISO 8601 |
  | `git` | plugin | branch and commit at creation |
  | `baseRef` | agent | optional commit SHA; only for stories about a change (uncommitted work: `HEAD`) |
  | `delivery` | plugin | marker for stories delivered through MCP (absent for file-drop stories) |
  | `steps` | agent | ordered list |
  | `threads` | plugin | story-level comment threads (see "Reader feedback loop") |

- Step fields: `id` (stable in the story; the plugin assigns one when the agent sends none), `title` (8 words at most), `comment` (Markdown subset), and an optional `location` with `file` (repository-relative path), `startLine` and `endLine` (1-based, inclusive), `anchorText` (the exact text of the first line of the range, as the agent sends it) and the range text stored by the plugin for re-anchoring. A step has exactly one location or none (a text-only step). Each step also has `threads` (comment threads owned by the plugin). `children` and `links` are reserved for later modes and are not used in v1.
- Thread fields: `id`, `kind` (`question`, `change` or `refresh`), `state` (`submitted`, `received`, `answered`, `done`, `declined`, `resolved`), and `messages`. Each message has an author (`reader` or `agent`), a round number, a time and the text. Agent replies also have an outcome and, for replies that changed the story, the automatic "Story changes" line. Drafts are not in the file; they stay in IDE storage until Submit.

### Agent API contract (MCP tools)

- `code_story_guide()` returns the writing guide as Markdown: the story-building rules, the ASD-STE100 rules in our own words, the format and an example. It matches the installed plugin version.
- `code_story_show(story, storyId?)` validates the story. When it is valid, the plugin saves it (a new story goes to `.codestory/local/`; an existing `storyId` is replaced in place, local or shared), opens it and plays it. It returns a report: story ID, file path, status (accepted or rejected), and lists of errors, corrections and warnings. Each item has the step number (or none for story-level items), a machine-readable code, a message and a fix hint.
- `code_story_list()` returns ID, title, type, date, branch, folder (local or shared), status (ok, or the number of moved, changed and lost steps) and the number of pending comments for each story.
- `code_story_get(storyId)` returns the full story.
- **Errors** reject the whole story, and nothing is saved: invalid structure; an empty story or more than 15 steps; a file larger than 256 KB; an absolute path, `..` or a symlink that leads out of the project; a file that does not exist; lines outside the file; anchor text not found near the given lines.
- **Corrections** keep the story: the anchor text was found a few lines away, and the lines were changed (for example "step 4: lines 120–131 changed to 123–134").
- **Warnings** keep the story: text-rule problems, a range longer than 30 lines, a `baseRef` that Git does not know.
- When the target project is not open, the MCP server returns an error that lists the open projects; the skill then falls back to the file drop, and the story appears when the project opens.
- File drop: the same validation runs when the watcher finds the file. Problems show in the tool window only, because there is no report channel to the agent.
- `code_story_feedback(storyId?)` returns the pending comments of one story or of all stories, oldest round first. Each item has the story ID, round, thread ID, kind, target (the story, or a step with ID, title, location and status), the comment text and the earlier messages of the thread. The result also lists the story's steps in "code changed" or "lost" status. Fetched comments become "received". They stay available to fetch until they are answered.
- `code_story_patch(storyId, operations)` edits a story without resending it. Operations: update a step (title, comment, or location with anchor text), insert a step after another step, remove a step, confirm a step (its current code becomes the new reference and its status returns to ok). It runs the same validation as `code_story_show` and returns the same kind of report.
- `code_story_reply(storyId, replies)` sends one reply per thread: an outcome (`answered` for questions, `done` for change and refresh items, `declined` with a reason) and the text.
  - The outcome must match the kind of the thread.
  - For `done`, every step in "code changed" or "lost" status must have been updated, replaced or confirmed after the round was fetched. Otherwise the reply is rejected with an error that lists those steps.
  - Writing checks give warnings.
  - The plugin adds the "Story changes" line itself (for example "Steps 3 and 4 updated, step 5 added, step 6 confirmed").
  - A reply to a thread that another session already answered gets the error "already answered"; the first reply wins.
- Threads belong to the plugin. When a story is replaced with `code_story_show(story, storyId)`, the plugin keeps each thread on its step by step ID; threads of removed steps move to the story level with the note "step removed". The guide asks agents to keep step IDs stable.
- Every tool result names the next call (for example "Next: answer with `code_story_reply`").

### Anchoring

- The agent sends file, start line, end line and anchor text (the first line of the range).
- On arrival, the plugin compares the anchor text with the text the IDE shows at the start line, including unsaved edits. The comparison ignores leading and trailing whitespace and line-ending differences. On a mismatch it searches nearby (about ±20 lines); a hit becomes a correction, no hit becomes an error.
- The plugin stores the text of the range. When a story opens or plays, it checks each anchor: in place means ok; found nearby or elsewhere in the file (a unique match is preferred) means moved, and the anchor is updated; not found means lost.
- While a document is open, live range markers move anchors as the developer types.
- A lost step still plays at its stored lines, with a warning line in the card: "The code changed. This step can be wrong."
- **Code changed:** when the anchor is found (in place or moved) but the code in the range differs from the code the step was written for, the step status is "code changed". This covers changes by the agent and by the developer. The step shows a badge and a note in the card until the agent updates or confirms it.
- Step statuses are therefore: ok, moved, code changed, lost.
- Moved anchors are written back to local stories only. For shared stories, corrections stay in memory.

### Change information

- `baseRef` is set by the agent for change stories only. Stories without it show no change information and no "Show diff".
- Per step: all lines in the range added since `baseRef` gives an "A" badge; some lines changed gives "M"; no change gives no badge. The plugin computes this from Git.
- "Show diff" opens the IDE diff viewer for the file (base revision against the current file), scrolled to the step range.
- Git only; other version control systems are not supported in v1.

### Floating step card

- The card floats beside the current range with a small pointer to the code. Showing or hiding it adds no space between lines and does not change code layout.
- Prefer unused space to the right of the range. The card must not cover code in the target range or the caret. Without room there, the card floats below or above the range, and otherwise over the code at the editor's right edge, so resizing the IDE or the tool window never moves it. Only when the editor is narrower than the card, show the same comment and controls in the Code Story tool window.
- Showing or repositioning the card does not take keyboard focus from the editor. Typing, selecting code and clicking in the editor do not dismiss the card or stop playback. Card controls remain clickable and keyboard-accessible.
- Recalculate placement from the range's current editor position during scrolling and resizing. Hide the floating card when its target leaves the viewport or its editor tab is inactive. Restore it when the target is visible again, unless the reader explicitly dismissed it. Browsing does not change the current step or scroll back automatically.
- Closing the card dismisses it for the current step and keeps playback position, the highlight and the badges. Clicking the current gutter badge or selecting the current step reopens the card. Playing another step shows that step's card. Stop and Finish remove the card in either location, the highlight and all story badges, and release presentation listeners. Editor and project disposal also clean up the floating card and its listeners.

### Playback rules

- A story delivered through MCP plays in the receiving IDE: the tool window opens, step 1 plays and the IDE window comes to the front (setting, on by default). The player starts after the story is accepted, independently of tool-window loading; the card does not take keyboard focus from the editor.
- A new file in `.codestory/local/` without the MCP marker (file drop) plays in every IDE that has the project open.
- A new file in `.codestory/shared/` (it came through Git) does not auto-play; the tree marks it as new.
- A step opens its file (in the preview tab when the IDE uses preview tabs), scrolls the range to the upper third of the editor and shows the card, highlight and badges. A text-only step shows in the details pane, and the editor stays as it is.
- Actions Next Step, Previous Step and Stop Story have default shortcuts that do not clash in the IntelliJ and Visual Studio/ReSharper keymaps; developers can change them in the Keymap.
- One story plays at a time per project; playing another story stops the first.
- On the last step, Next becomes Finish. Finish and Stop remove the card, the highlight, the gutter number and all story badges.
- Play continues at the last step of that story (kept per user in IDE storage). Restart is in the context menu.
- When the agent replaces the story that is playing, the plugin reloads it and stays at the same step number (or the last step, if the new story is shorter).
- ✓ visited marks last for the current IDE session only.

### Tool window

- Toolbar: Play/Stop, Prev/Next, Share/Unshare, Delete, Copy as Markdown, Submit round (with the draft count), Refresh.
- Tree: repository (only when the project has several) → Local / Shared → stories (title, date, branch, type icon, newest first, warning with the count of lost or changed steps, draft count) → steps (number, title, file and line, A/M badge when the story has a base, ✓ when visited, open-thread count, a dot for unread answers).
- Details pane: for a story, the summary, request, "Written by <agent> (<model>)", date, branch with commit, a note when the current branch differs, the diff base, the story-level threads and "Comment on story"; for a step, the comment, "Show diff" and the step's threads.
- Context menu: Restart, Comment, Request refresh, Show diff, Share/Unshare, Delete.
- Clipboard action: "Copy as Markdown" (title, summary, steps with repository-relative links with line ranges such as `path#L42-L57`, and comments; threads are not included).
- Not in v1: rename, edit, reorder, import, automatic cleanup, story version history (Git keeps the history of shared stories).

### Storage and sharing

- `.codestory/` lives at the Git repository root, and story paths are relative to it. Without Git, the project folder is used. With several repositories in one project, each repository has its own `.codestory/`, and one story stays inside one repository.
- New stories go to `.codestory/local/`. The plugin creates a `.gitignore` inside it that ignores everything, so no root `.gitignore` change is needed.
- Share moves a story to `.codestory/shared/` (committed with the branch); Unshare moves it back. The story ID does not change.
- Reader state (last step, visited marks, comment drafts, unread markers) is never written into story files.

### Text rules and checks

- Format: a Markdown subset with inline code, bold, bullet lists and links (http/https and project files). No headings, images, tables or HTML. Links open only on click.
- Limits: story and step titles of 8 words or fewer; a summary of 1–3 sentences; a comment of 1–3 sentences or a lead sentence and up to 4 list items, 60 words at most; sentences of 20 words or fewer for instructions and 25 or fewer for descriptions; code names count as one word; English only.
- v1 checks (own Kotlin code): sentence length, sentences per comment, total words, title length, summary length, semicolons and contractions. Passive voice and -ing words are optional hints, off by default, because code text such as "string" or "mapping" gives false alarms. There is no check against the ASD-STE100 dictionary (its copyright does not allow us to ship it).
- Results are warnings only. They go into the agent's report, and the guide tells the agent to fix them once and send the story again. The reader's UI does not show them.
- Agent answers in threads follow the same rules, with larger limits: 1–5 sentences, or a lead sentence and up to 6 list items, 100 words at most. Answers may link to project files with line ranges. A "declined" reply must give a reason in 1–3 sentences.
- Reader comments are not checked; readers write freely in the same Markdown subset.

### Reader feedback loop

- **Comments:** a reader comments on a step (the 💬 button in the card, or the step's threads in the details pane) or on the whole story ("Comment on story" in the details pane). Each comment has a kind, chosen with a toggle: Question (default) or Change request. There are no comments on separate line ranges inside a step; the comment text can quote a line.
- **Drafts:** comments start as drafts in IDE storage (per user). Drafts can be edited and deleted. The story row and the "Submit round (N)" button show the draft count. A shortcut opens "Comment on current step"; Ctrl+Enter saves a draft.
- **Rounds:** "Submit round" (toolbar and details pane, disabled without drafts) writes all drafts of the story into the story file as one numbered round (round 1, 2, …). Submitted comments are read-only and show "waiting for agent". A submitted comment can be withdrawn until an agent fetches it.
- **Handing over:** after Submit, the IDE shows "Waiting for your agent: tell it `/code-story feedback`" with a copy button (`$code-story feedback` in Codex). Any agent session in the project can process the round. There is no server push: no agent wakes up on a message from the MCP server.
- **Thread states:** submitted → received (an agent fetched it; the IDE shows "agent working") → answered (question), done (change or refresh item) or declined (with a reason). A reader reply in a thread adds a new draft and reopens the thread. A reader can resolve a thread; resolved threads are hidden until "Show resolved" is on.
- **Questions:** the agent answers in the thread and never changes code. If it thinks a change is needed, it says so, and the reader can file a change request. The agent may also improve the step text or insert steps when the question shows that the story was unclear; the "Story changes" line lists such changes.
- **Change requests:** the agent applies the change directly (its normal permission prompts still apply), updates every affected step, and replies "done" with a short summary of the code changes and the changed files. The plugin rejects "done" while any step is in "code changed" or "lost" status and was not updated, replaced or confirmed after the round was fetched.
- **Request refresh:** this action adds a refresh item to the story's pending round, so the agent updates stale steps through the same `/code-story feedback` path.
- **Arrival:** when replies arrive, a notification shows the number of answers with an Open action, the tree marks steps with unread answers, and a story that is playing reloads at the same step.
- **Storage and sharing:** threads are part of the story file, so they travel with a shared story. A reader who comments on a shared story changes their own working copy of that file on purpose (unlike automatic anchor corrections, which never touch shared files). Drafts and unread markers stay in IDE storage.
- **Who:** the local reader and their own agent. Comments are not sent to another person (for example the story's author).
- **Hooks (optional):** the plugin keeps a small pending-feedback index in `.codestory/local/`. "Check setup" and the README offer hook snippets to copy: for Claude Code and Codex at session start and on each prompt, and for Copilot CLI at session start. The hooks tell the agent that feedback is pending, so the agent mentions it and processes it when the developer agrees. The plugin never installs hooks itself. Copilot's Rider plugin has no suitable hook.
- **Listening mode** (the agent keeps waiting for rounds, so Submit reaches it at once) is planned for v1.1. It is fragile across agents in v1: tool-call limits range from 30 to 300 seconds, Claude Code probably cuts calls to the IDE's MCP server after about 60 seconds unless the user raises a timeout, and the limits of Copilot in Rider and of Junie are unknown.

### Agent skill

- One skill named `code-story` in the Agent Skills format: a short `SKILL.md` with the workflow (use the conversation context, explore when needed, call `code_story_guide`, build the story, call `code_story_show`, fix errors and warnings once, tell the developer) and a references folder with the guide, the JSON Schema and an example for the file-drop path.
- The story-building rules: (1) context from the conversation first, otherwise explore; verify every location by reading the file. (2) Order by story type: change (entry point → core logic → data → side effects → tests and configuration); exploration (runtime flow, one hop per step); bug trace (trigger → path → failure point → fix); review handoff (like change, plus risks and edge cases). (3) 3–12 steps, 15 at most; otherwise narrow the subject or split it into several stories and tell the developer. (4) One idea per step; a range of the key lines, usually 30 or fewer. (5) Skip mechanical changes and mention them in the summary in one sentence at most.
- Invocation: `/code-story <request>` in Claude Code and GitHub Copilot (Rider plugin and CLI); `$code-story <request>` in Codex CLI.
- When the Code Story MCP tools are not available, or the project is not open, the skill writes the story file into `.codestory/local/` instead.
- **Feedback mode** (`/code-story feedback [storyId]`, or `$code-story feedback` in Codex):
  1. Fetch the pending comments with `code_story_feedback`, oldest round first.
  2. Questions: answer from the story, the threads and the code. Never change code; if a change seems needed, say so and suggest a change request. Improve the step text or insert steps with `code_story_patch` when the question shows that the story was unclear.
  3. Change requests: make the code change, then update every affected step (re-anchor, rewrite or confirm), then reply "done" with a short summary. Decline with a reason when the change cannot or should not be made.
  4. Refresh items, or no comments but steps in "code changed" or "lost" status: refresh those steps.
  5. At the end, summarize in the chat what was answered and changed, and point to the IDE for the details.

### Setup and onboarding

- The plugin bundles the skill. "Install Code Story skill" writes it to `.agents/skills/code-story/` and `.claude/skills/code-story/`, at user level (default) or project level. Installed copies carry a version; after a plugin update, the plugin offers to update them. The skill is also published in this repository for manual installation.
- When the IDE's MCP server is off, a notification explains why Code Story needs it and opens Settings | Tools | MCP Server. There the developer switches it on and uses the IDE's Auto-Configure for their agents. The plugin never changes this setting itself.
- "Check setup" shows: the MCP server on or off, the Code Story tools registered, and the skill installed (where and which version). It also offers the optional feedback hook snippets to copy.
- First run: one notification with two steps (install the skill, switch on the MCP server).
- Settings page (Settings | Tools | Code Story): bring the IDE to the front on a new story (on), optional style hints (off), skill install location (user level), highlight color (theme default).

### Security and privacy

- Code Story tools only create, read and update stories and their threads. No tool runs code or commands, and nothing writes outside `.codestory/`. CodeTour-style `commands` are not supported. Code changes for change requests are made by the agent with its own tools and permission prompts, never by the plugin.
- Hooks are offered as snippets only; the plugin never installs them.
- Paths must be inside the project; absolute paths, `..` and symlinks that lead out of the project are rejected.
- Text is rendered without HTML; links allow only http/https and project files and open only on click.
- Limits: 15 steps, the text limits above, 256 KB per story file.
- Every story shows "Written by <agent> (<model>)".
- No network calls and no telemetry.
- Performance: the watcher covers `.codestory/` only; anchors are checked only when a story opens or plays; writes are atomic.

## Testing Decisions

- **What makes a good test:** it tests external behavior through the highest seam, not implementation details. Given project files and a story sent through the agent API (or dropped as a file), a test asserts the report, the saved file and the observable player state (active story, current step, step statuses). Internal classes are not tested directly.
- **Primary seam: the agent API.** IDE-level tests (IntelliJ Platform test framework, light project fixtures with in-memory files) call the tool handlers (`code_story_show`, `code_story_list`, `code_story_get`, `code_story_guide`) and assert reports, files and player state. The file-drop path is tested at the same seam by creating files in `.codestory/local/` in the fixture. Sharing, drift (edit the fixture document, then assert moved/lost) and change information (fixture Git history) also go through this seam.
- **Fast seam: the story core.** Plain Kotlin unit tests, mostly table-driven, for the format round trip, the validation rules, anchor matching (in place, moved nearby, moved far, lost, whitespace and line endings, duplicate matches), the text checks and Markdown sanitizing.
- **Playback:** IDE-level tests cover MCP auto-play, next / previous / finish / stop, one story per project, resume / restart, card dismissal and reopening, and cleanup of the card, highlighters and gutter badges. Playback adds no code-line inlays.
- **UI:** the card, gutter and tool window are verified by a short manual checklist before each release in Rider (C#) and IntelliJ IDEA (Java/Kotlin), in light and dark themes. Check unchanged code layout, editor focus, pointer placement, scrolling and resizing, hiding and restoring the card, dismissal versus Stop, and the tool-window fallback in narrow or split editors. No UI automation in v1.
- **Compatibility:** the Plugin Verifier runs in CI against Rider and IntelliJ IDEA 2025.3, 2026.1, 2026.2 and the current early-access build.
- **End to end (manual, release gate):** Claude Code, Codex CLI, GitHub Copilot in Rider and Copilot CLI each create a story through MCP; the file drop works with the MCP server off; a shared story plays in a second clone.
- **Feedback loop at the agent API seam:** IDE-level tests submit a round in the fixture, call `code_story_feedback`, `code_story_patch` and `code_story_reply`, and assert thread states, the "Story changes" line, the "already answered" rule, thread preservation on replace, and the "done" check (edit the fixture code under a step, then assert that "done" is rejected until the step is updated or confirmed). The thread state machine and the "Story changes" calculation also get story-core unit tests.
- **Prior art:** this repository has no code yet. External references: Marginalis' anchor-policy tests (Apache-2.0) for the fast seam, and Walkthrough's MCP toolset tests (MIT) for the agent API seam.

## Out of Scope

- Editing stories in the IDE: rename, text edit, reorder, manual "add step". The agent is the only author.
- Later modes: drill-down outline with a live question loop (v2), diff walkthrough with steps on the old side of a diff (v3), flow map (v4). An annotation layer is not planned.
- CodeTour import and export (v1.1).
- C# symbol anchors and any ReSharper (.NET) part.
- Remote development, split mode and JetBrains Client.
- Ticket-tracker integration (Jira, Azure DevOps, YouTrack).
- Checks of individual words against the ASD-STE100 dictionary; languages other than English.
- Automatic cleanup of old stories; story version history.
- LLM calls from inside the plugin.
- Change information for version control systems other than Git.
- JetBrains Marketplace publication (planned after v1 has been in daily use and the name has been checked).
- Listening mode for feedback rounds (v1.1), comments sent to another person (for example the story's author), comments on separate line ranges inside a step, and automatic installation of agent hooks.

## Further Notes

- **Prior art.** Walkthrough (MIT) already pushes agent steps into JetBrains IDEs through MCP with floating popups. It has no drift handling, sharing or wording rules, and is not tested on Rider. Marginalis (Apache-2.0) solves review conversations and shows how to re-anchor by text. Code Story reuses ideas and, where useful, code from both, with license notices. See `brainstorm.md` for the full comparison.
- **MCP server is off by default.** Onboarding is part of the product: without the switch-on step, only the file drop works.
- **The MCP toolset API is not formally frozen.** Compile against the oldest supported version and verify against all newer ones; watch release notes for toolset changes.
- **Floating cards can cover code.** Keep the target code and caret visible; use the tool-window fallback when there is no room. Story cards never use component or block inlays.
- **ASD-STE100 copyright.** Do not ship the dictionary or the specification text. The guide paraphrases the rules.
- **No server push.** No supported agent wakes up on a message from the MCP server, so feedback rounds are pulled by the agent (`/code-story feedback`), with optional hooks as a reminder. Listening mode comes in v1.1.
- **Roadmap.** v1.1: CodeTour import and export, and listening mode for feedback rounds. v2: drill-down mode and the live question loop. v3: diff walkthrough mode. v4: flow map mode. If needed: C# symbol anchors, remote development support, a word-list check against a user-supplied copy of the ASD-STE100 dictionary. Marketplace after a name check and a few weeks of daily use.
- **v1 is done when:**
  1. In Rider 2026.2 on a real C# solution, `/code-story "<request>"` in Claude Code creates a story through MCP; it plays, and every step points at the right code.
  2. The same flow works with Codex CLI (`$code-story`), GitHub Copilot in Rider and Copilot CLI.
  3. With the MCP server off, the file-drop fallback works.
  4. In the usual case, the agent fixes a story from the report's errors and warnings without help.
  5. A story that is shared, committed and checked out in a second clone plays there.
  6. Editing code during playback keeps the anchors right; after large edits, lost steps show a warning.
  7. The Plugin Verifier passes for Rider and IntelliJ IDEA from 2025.3 to the current early-access build, and the unit and IDE tests pass in CI.
  8. The manual checklist passes in Rider (C#) and IntelliJ IDEA (Java/Kotlin), in light and dark themes.
  9. A reader comments on steps, submits a round, and `/code-story feedback` answers the questions in the story and applies a change request; the affected steps are updated or confirmed, and "done" is rejected until they are.
