# Code Story

Code Story is a JetBrains IDE plugin for guided code walkthroughs, with Rider as the
primary target. Ask your AI coding agent for a story, for example
`/code-story "Explain the main change for this ticket"`. The agent writes a short story
of ordered steps and sends it to your running IDE. Each step opens its file, highlights
the code that it explains and shows a floating card with a short comment. Stories stay on
the right code while you edit, travel with your branch when you share them, and carry
your questions and change requests back to the agent.

Code Story works with Claude Code, Codex CLI, GitHub Copilot in Rider and GitHub Copilot
CLI. See [CHANGELOG.md](CHANGELOG.md) for the release notes, [spec.md](spec.md) for the
product specification and [docs/v1-verification.md](docs/v1-verification.md) for the v1
release checks.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/step-card-dark.png">
    <img src="docs/images/step-card-light.png" alt="A story step in the editor: the explained code is highlighted, the current step’s gutter badge is filled, and the step card sits to its right with the step title, the comment, an answered question, the comment input, and Prev, Next and Stop." width="720">
  </picture>
</p>

*The step card beside the highlighted code. The images in this README are illustrations of the layout, drawn in light and dark themes.*

- [Requirements](#requirements)
- [Set up your agent](#set-up-your-agent)
- [Use](#use)
- [Settings](#settings)
- [Agent tools](#agent-tools)
- [Story files](#story-files)
- [Known limits](#known-limits)
- [Privacy and security](#privacy-and-security)
- [Develop](#develop)
- [Release](#release)

## Requirements

- Rider or IntelliJ IDEA 2025.3 or newer, running on your machine. Other IntelliJ-based
  IDEs from 2025.3 also load the plugin; v1 is tested in Rider and IntelliJ IDEA.
- An AI coding agent on the same machine: Claude Code, Codex CLI, GitHub Copilot in Rider
  (agent mode) or GitHub Copilot CLI.
- Optional: the bundled MCP Server plugin, so that agents send stories directly and get
  validation reports. Without it, agents write story files instead.
- Optional: the bundled Git plugin, for branch and commit metadata and change information.
- Optional: Python 3, for the [feedback hook snippets](#optional-feedback-hooks).

## Set up your agent

Your agent needs two things: the `code-story` skill, and a connection to the IDE's MCP
server. On the first run, the plugin shows one notification with both steps.

### 1. Install the skill

Choose **Tools | Code Story | Install Code Story skill** (also in the Code Story toolbar).
It writes the same skill to `~/.agents/skills/code-story/` and
`~/.claude/skills/code-story/`, so that every supported agent finds it. To install the
skill into the project instead, select **Install the Code Story skill in the current
project** in **Settings | Tools | Code Story** and run the action again: the copies then go
to `.agents/skills/code-story/` and `.claude/skills/code-story/` at the Git repository
root (the project folder without Git). After a plugin update, the plugin offers to update
older copies. You can also copy [`skills/code-story`](skills/code-story) into these
folders by hand.

Without the IDE action, you can install the skill from this repository with
[`npx skills`](https://github.com/vercel-labs/skills):

```bash
npx skills add Hubert-Rybak/Jetbrains-Code-Story-Plugin --skill code-story
```

The command asks which agents to install for. Add `-g` to install into your user folder
instead of the current project.

### 2. Switch on the MCP server

Open **Settings | Tools | MCP Server**, select **Enable MCP Server**, confirm with
**Enable**, and click **Apply**. The confirmation lists the access that local programs get
to your open projects. Code Story never changes this setting itself.

### 3. Connect your agent

The steps are the same for every agent:

1. On the **MCP Server** settings page, click **Auto-Configure** next to your agent in
   **Clients Auto-Configuration**. If your agent is not listed, use **Manual Client
   Configuration** to copy the server address (HTTP stream, SSE or stdio, whichever your
   agent supports) and add it to the agent's MCP configuration.
2. Restart the agent, or reload its skills and MCP servers. Its MCP list then shows the
   IDE's server.
3. Start the agent in the folder of a project that is open in the IDE, and run the skill
   with your agent's skill command, for example
   `/code-story "Explain the main change for this ticket"`. Agents that use another
   prefix for skills, such as `$code-story` in Codex, work the same way.

### Check setup

**Tools | Code Story | Check setup** (also in the Code Story toolbar) shows whether the
MCP server is on, the Code Story tools that it registered, the bundled skill version, and
every installed skill copy with its version. **Copy feedback hooks** in the same dialog
copies the [optional feedback hooks](#optional-feedback-hooks). When the MCP server is
off, a notification links to its settings page.

### Without the MCP server

When the Code Story tools are missing, or the target project is not open in the IDE, the
skill writes a complete story file to `.codestory/local/`, using a temporary file and
then a rename. The plugin watches `.codestory/` only, including files dropped before the
project opens. It checks new and changed local stories against the same location rules
and unsaved editor text as MCP. A file drop has no report channel to the agent: errors
appear in the tool window and clear when the file is fixed or removed. Valid drops play
in every IDE that has the project open. MCP-delivered stories carry `"delivery": "mcp"`,
so other IDEs list them without auto-playing them.

After this version accepts a file drop, the IDE remembers it across project restarts.
Reopening an unchanged story preserves its saved code and does not play it again. Later
code edits show as **code changed** or **lost**. Files accepted only by an older version
still need one arrival check because that version did not save an acceptance record.

## Use

### Ask for a story

Open the project in the IDE, and ask your agent:

```text
/code-story "Explain the main change for this ticket"
/code-story "How does the payment flow work?"
/code-story "How can this NullReferenceException occur?"
/code-story "Prepare a review handoff for this branch"
```

The agent uses the conversation, and explores the code when
the conversation does not cover the subject. It writes a story with a type (change,
exploration, bug trace or review), a title, a short summary and 3 to 12 steps (15 at
most). Each step explains one idea about a small code range, in short sentences of simple
English. Text-only steps can introduce or close the story.

The agent first calls `code_story_guide` for the writing rules, format and example
bundled with the installed plugin version. It calls `code_story_show` with the story and
the `projectPath` of the project. The IDE checks the story against the project files. A
valid story is saved in `.codestory/local/` at the Git repository root (the project
folder when there is no Git) and appears in the tool window. It starts at step 1 and
brings the IDE to the front (see [Settings](#settings)). `.codestory/local/` has its own
`.gitignore`, so local stories stay out of Git.

A story with errors is not saved: the tool returns each error with the step number, a
code, a message and a fix hint, so the agent can correct the story and send it again.
Each step location carries `anchorText`, the exact text of its first line. The IDE
compares it with the line that it shows, including unsaved edits; indentation, trailing
spaces and line endings do not matter. When the text is within 20 lines of the given
line, the IDE moves the range and the report lists a correction (for example
`Step 4: lines 120–131 changed to 123–134.`). When it cannot find the text, it rejects
the story. The plugin stores the code of each range in the story.

The report also includes writing warnings and warnings for ranges longer than 30 lines.
They never block a story and appear only in the agent report. The guide asks the agent
to fix them once and replace the story with its returned ID. Mechanical checks cover
title, sentence and comment lengths, list structure, semicolons and contractions. Code
names count as one word. Optional passive voice and -ing hints are off by default.

To revise a story, ask your agent. It finds the story with `code_story_list` and
`code_story_get`, and replaces it with `code_story_show(story, storyId)`; the same file
and creation metadata are kept. A playing story reloads at the same step number, or the
last step if the revision is shorter.

### Read a story

The current step shows a floating card beside the highlighted code, with "Step N of M",
the step title, the comment, the comment threads of the step, a comment input and
**Prev**, **Next** and **Stop**; the tool-window toolbar has the same buttons. On the last
step, **Next** becomes **Finish**. The step text and the threads scroll; the comment input
and the buttons stay in view. The card leaves the target code and the caret visible and
does not take keyboard focus, so you can type, select code and click in the editor while
the story plays. Drag the card's right edge, bottom edge or corner grip to resize it.
Every card keeps that size, and a double-click on the grip restores the default size.
When the editor has no room for the card, even at a smaller width, the same card appears
in the tool window. Text-only steps show their comment in the details pane and leave the
editor as it is. Steps open in the preview tab when the IDE uses preview tabs.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/tool-window-dark.png">
    <img src="docs/images/tool-window-light.png" alt="The Code Story tool window: a toolbar, stories grouped under Local and Shared with dates, branches and step statuses, a details pane for the selected step, and the comment input below it." width="720">
  </picture>
</p>

Numbered gutter badges jump to other steps in the same file; the badge of the current
step is filled with the accent color. Closing the card keeps your
place and the highlighted code; click the current badge or step to reopen it. Scrolling
and switching tabs hide and restore the card without moving your caret or scrolling
back. **Stop** and **Finish** remove the card, the highlight and the badges.

The **Code Story** tool window lists stories newest first, with title, date, branch and
type icon, and their steps with number, title, file and lines. ✓ marks show the steps
that you visited in this IDE session. Selecting a story shows its summary, original
request, "Written by <agent> (<model>)", creation date, branch and commit. A note
appears when the current branch differs from the branch where the story was created.
Without Git, the metadata is shown as unavailable.

**Play** resumes the selected story at your last step. Right-click the story and choose
**Restart** to start at step 1. One story plays at a time in a project. Reading positions
stay in your IDE workspace and never go into story files.

The default shortcuts start with **Ctrl+Alt+Shift+Z**; release those keys, then press
**Right** for **Next Step**, **Left** for **Previous Step**, **End** for **Stop Story**,
or **C** for **Comment on Current Step**. Change them in **Settings | Keymap** by
searching for the action name.

Comments support inline code, bold, bullet lists and links to http/https URLs or
repository files (`src/App.kt#L2-L5`). Links open only when clicked. Headings, images,
tables and HTML stay plain text.

### Stories about a change

A story about a change can set `baseRef`: the commit before the change, or `HEAD` for
uncommitted work. The plugin saves the full SHA of that commit, so the base stays the
same after the work is committed. Each step then shows **A** in the tree when all of its
lines are new since the base, or **M** when some of them changed; steps without changes
show no badge. **Show diff**, in the step details and the context menu, opens the IDE
diff viewer: the file at the base commit against the current file, scrolled to the
step's lines. A renamed file is compared with its old path. A `baseRef` that Git does not
know gives an `unknown-base-ref` warning in the report, and the story then shows no
change information, like a story without a base. Without the Git plugin, change
information is off.

### When the code changes

While a story plays, live range markers follow your edits, so the card and the highlight
stay on the right code. When a story is listed or played, the plugin finds the code of
each step again. A step is **ok**; **moved** (the lines are updated); **code changed**
(the range has other code than the step explains: the tree shows a badge and the card a
note); or **lost** (the code is gone: the step plays at its stored lines, and the card
warns "The code changed. This step can be wrong."). The story row shows the number of
lost and changed steps, and `code_story_list` reports the status. Moved lines are saved
in local stories only; shared story files never change, and their corrections stay in
memory. To bring stale steps up to date, use **Request refresh**
([below](#questions-and-change-requests)).

### Share and manage stories

The tree groups stories under **Local** and **Shared**. **Share** moves a story to
`.codestory/shared/` so you can commit it with the branch; **Unshare** moves it back. Its
ID and creation metadata stay the same. **Delete** asks for confirmation before removing
the file. These actions are in the toolbar and context menu. **Copy as Markdown**, in
both menus, copies the title, summary, and steps with repository-relative line links and
comments for a pull request description. Reader threads are not included.

Shared stories that arrive through checkout or pull appear automatically with a **new**
marker. They do not start playback. Playing one clears the marker in your IDE workspace,
without changing the shared file.

With several Git repositories mapped in one project, the tree groups by repository and
each repository has its own `.codestory/`. A story must stay inside one repository.

If a shared arrival has the same ID as a local story, both files remain separate: comments
and unsent comments belong to the selected file. Agent tools report `ambiguous-story-id`
until the two files have different IDs. Change both the filename and the JSON `id` when
resolving such a collision.

### Questions and change requests

Ask your agent about a story, or ask it for a code change, without leaving the IDE.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/comments-dark.png">
    <img src="docs/images/comments-light.png" alt="The comments of a step: an answered question, a change request that the agent is working on, a question that waits for the agent, and the comment input with a typed question and the Post button." width="720">
  </picture>
</p>

1. **Write a comment.** The card has a comment input below the step text, next to the
   playback buttons, so it never scrolls away. In the tool window, the details pane has
   the same input below the details of the selected step or story; **Comment** in the
   context menu moves the focus to it. **Ctrl+Alt+Shift+Z**, then **C**, comments on the
   current step. Choose **Question** (the default) or **Change request**.
2. **Post it.** **Post** (Ctrl+Enter) writes the comment into the story file at once.
   Until an agent fetches it, it shows "Waiting for agent", and **Withdraw** takes it back
   into the input for editing. A withdrawn comment, or one that could not be written,
   stays in your IDE workspace as an unsent comment with **Post**, **Edit** and
   **Delete**, and the story row shows how many unsent comments it has.
3. **Your listening agent picks it up.** The bundled skill calls `code_story_listen`
   after showing a story and after each reply, so a posted comment needs no extra chat
   prompt. A fetched comment shows "Agent working". If your agent has stopped, tell it
   `/code-story feedback`; the comments have buttons to
   copy the command.
4. **Read the answers.** A notification tells you that answers arrived, and **Open**
   shows the story. The answers appear under your comments, in the card and in the
   details pane, and a story that is playing keeps its step. Step rows show open-thread
   counts and a dot for unread answers; showing the threads clears the dot.
5. **Continue or close.** **Reply** in a thread turns the input into a reply; posting it
   opens the thread again. **Resolve** closes a thread; resolved threads are hidden until
   you select **Show resolved**.

Listening is enabled by default. Install the current bundled skill with **Tools | Code
Story | Install Code Story skill** to enable this workflow. Disable **Let connected
agents listen for submitted feedback automatically** in **Settings | Tools | Code Story**
for manual feedback only. You can also cancel the waiting agent or tell it to stop.
The tool waits up to 25 seconds per call; the skill reconnects after timeout and after
answering a round. Unsent comments do not wake it. Listening requires an active agent
session; it cannot restart an agent that ended its turn. Optional hooks only remind
sessions that are not listening.

The listening tool uses the same fetch path as `code_story_feedback`, oldest round first.
Comments that wait for an agent together share a round number, which the agent uses in
its replies.
The agent answers each comment with `code_story_reply`: `answered` for questions, `done` for change
or refresh requests, or `declined` with a reason.

- **Questions:** the agent answers from the story, the thread and the code, and never
  changes code. When a question shows that the story was unclear, it improves the step
  text or inserts steps with `code_story_patch`. The plugin then adds a **Story changes**
  line to the reply.
- **Change requests:** the agent applies the change with its normal tools and permission
  prompts, updates every affected step, and replies `done` with a short summary and the
  changed files, which appear with the reply. The plugin rejects `done` with a list of
  steps that still need review: every step in **code changed** or **lost** status must
  be updated, replaced or confirmed after the round was fetched, and a later code edit
  requires another review.
- **Request refresh**, in the story or step context menu, adds a refresh item to the
  story’s latest unfetched round (or starts a new round). The listening agent picks it
  up automatically; use `/code-story feedback` if the agent has stopped. With no pending
  comments, feedback mode still finds and refreshes **code changed** and **lost** steps.

Threads are part of the story file, so they travel with a shared story, and a comment on
a shared story changes that file. Unread state stays in your IDE workspace across
restarts and never changes a story file. When an agent revises a story, each thread stays
on its step (by step ID); threads of removed steps move to the story with the note “step
removed”. Agent answers follow the writing rules with larger limits (1 to 5 sentences, or
a lead sentence and up to 6 list items, 100 words at most); the checks give warnings only.
A reply to a comment that is already answered gets `already-answered`, so two agent
sessions never answer the same comment twice.

### Optional feedback hooks

The plugin maintains `.codestory/local/pending-feedback.json`, a small local index of
pending story IDs, folders, counts and rounds. It includes submitted and fetched
feedback, omits unsent comments, comment text and code, and clears entries as feedback
is answered, withdrawn or deleted. The IDE refreshes it on project open and story file
changes.
**Check setup → Copy feedback hooks** copies a small Python helper and a ready
configuration for each supported agent. Save the helper as `~/.codestory/feedback-hook.py`
and merge the configuration into your agent's hook settings. The plugin never installs
hooks. The helper needs Python 3. The reminders ask the agent to mention pending feedback
and process it when you ask or agree. They do not push feedback to the agent or start
feedback mode.

## Settings

**Settings | Tools | Code Story**:

| Setting | Default | What it does |
|---|---|---|
| Bring the IDE to the front on a new story | On | A new story brings the IDE window to the front. When it is off, the story still opens and plays. |
| Include passive voice and -ing hints in agent reports | Off | Adds these optional writing hints to the agent's reports. Code text such as "string" or "mapping" can give false hints. |
| Install the Code Story skill in the current project instead of the user folder | Off | **Install Code Story skill** writes to `.agents/skills/` and `.claude/skills/` at the repository root instead of your home folder. |
| Let connected agents listen for submitted feedback automatically | On | Waiting agents pick up each posted comment. Turning this off ends waiting calls; manual feedback remains available. |
| Highlight color | Use theme default | The background of the current step's range. Clear **Use theme default** to choose a color; transparency is supported. |

Other places:

- **Settings | Keymap**: **Next Step**, **Previous Step**, **Stop Story** and
  **Comment on Current Step**.
- **Settings | Tools | MCP Server**: the IDE's MCP server (see
  [Switch on the MCP server](#2-switch-on-the-mcp-server)).
- **Settings | Appearance & Behavior | Notifications**: the **Code Story** notification
  group.

## Agent tools

The plugin adds eight tools to the IDE's MCP server. Each call needs the `projectPath` of
an open project, and each result names the agent's next call. `repositoryPath`, the
absolute repository root, selects the repository when paths or story IDs match several
repositories, or for a text-only story.

| Tool | What it does |
|---|---|
| `code_story_guide` | Returns the writing guide, the story format and an example for the installed version. |
| `code_story_show` | Checks a story; when it has no errors, saves and plays it. With `storyId`, replaces that story in its local or shared file. Returns errors, corrections and warnings. |
| `code_story_list` | Lists local and shared stories: ID, title, type, date, branch, repository path, folder, status (`ok`, `moved`, `code-changed` or `lost`, with step counts) and `pendingComments`. |
| `code_story_get` | Returns the full saved story with its comment threads. |
| `code_story_patch` | Edits a story with ordered `update`, `insert`, `remove` and `confirm` operations. |
| `code_story_listen` | Waits for posted comments by default. Returns feedback, a timeout to reconnect, or stopped when listening is disabled. |
| `code_story_feedback` | Returns the reader's comments that wait for an agent, oldest round first, and the stale steps. |
| `code_story_reply` | Saves one reply per comment: `answered`, `done` with `changedFiles` (`[]` when no code files changed), or `declined` with a reason. |

`code_story_patch` operations: `update` (stepId; title, comment or location),
`insert` (afterStepId and a complete step), `remove` (stepId) and `confirm` (stepId).
Confirm makes the current code the step’s new reference and returns its status to **ok**;
the agent must read the current code first and check that the explanation still fits. A
missing file or range needs a new location or removal. Locations include anchor text; an
explicit null makes a step text-only. A patch checks the whole structure and validates
edited steps like `code_story_show`; untouched steps keep their code references,
including stale steps. Any error saves nothing. Threads and creation metadata stay
intact. Replies show a **Story changes** line for the net story edits made while the
comment was received.

## Story files

- Stories are in `.codestory/local/` (ignored by Git) and `.codestory/shared/` (committed
  with the branch) at the Git repository root, or in the project folder without Git.
- Files are named `<yyyy-MM-dd>-<slug>.story.json`; the name without `.story.json` is the
  story ID. The plugin adds a number suffix when a name already exists.
- The format is described by the JSON Schema in
  [`schema/story-v1.schema.json`](schema/story-v1.schema.json) (`schemaVersion` 1).
  Paths are relative to the repository root; line numbers are 1-based and inclusive.
- A story has at most 15 steps, and a file at most 256 KB.
- The plugin records the creation time and Git state, and assigns stable IDs to steps
  that have none.
- `.codestory/local/pending-feedback.json` is the local index for the
  [feedback hooks](#optional-feedback-hooks).
- Reading positions, visited marks, unsent comments, the step card size and unread
  markers stay in IDE storage, never in story files.

## Known limits

- **Local IDE only.** Remote development, split mode and JetBrains Client are not
  supported.
- **Install from disk.** The plugin is not on JetBrains Marketplace yet, and the IDE does
  not update it automatically.
- **Tested IDEs.** v1 is tested in Rider and IntelliJ IDEA. Other IntelliJ-based IDEs from
  2025.3 load the plugin without such tests.
- **The MCP server is off by default.** Only you can switch it on. Without it, agents
  deliver stories as files and get no validation report.
- **One IDE per agent connection.** An agent talks to the MCP server of the IDE that it
  is configured for. When the project is not open in that IDE, the skill writes a file.
- **Listening needs an active agent.** Posted comments are picked up automatically while
  the agent keeps `code_story_listen` active. The plugin cannot restart an ended agent
  turn. If the client disconnects or cannot maintain waiting calls, resume with
  `/code-story feedback`. Hooks are optional reminders, not a requirement for listening.
- **Text anchors.** Steps anchor to a file, a line range and the text of the first line,
  not to code symbols. After large edits, steps can be **lost** until the agent
  refreshes them.
- **Git only.** Change information needs Git and the Git plugin; other version control
  systems are not supported.
- **Agent-written stories.** You cannot edit, rename or reorder stories or steps in the
  IDE; ask the agent. One story plays at a time in a project, and each step has one code
  range at most.
- **Writing checks.** The checks are mechanical and for English only. There is no check
  against the ASD-STE100 dictionary.
- **Your own agent.** Comments go to your agent, not to the author of a shared story.
- **No CodeTour import or export** yet; it is planned for v1.1.

## Privacy and security

- The plugin makes no network calls and sends no telemetry.
- Code Story tools only create, read and update stories and their threads in
  `.codestory/`. They never run code or commands. Code changes for change requests are
  made by your agent, with its own tools and permission prompts.
- The IDE's MCP server accepts calls from local programs and has its own tools. Its
  confirmation dialog lists what they can do; Code Story adds only the tools above.
- Story files from Git are untrusted input. Paths must stay inside the repository:
  absolute paths, `..` and symbolic links that lead outside are rejected. Text is shown
  without HTML; links allow only http/https and project files, and open only on click.
- Every story shows "Written by <agent> (<model>)".
- The plugin never installs hooks and never changes the MCP server setting.

## Develop

This single-module Kotlin project is based on the
[official IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template/tree/b7b700870b23e1323353a0350a1acd9cc6d2b225).
It compiles against IntelliJ IDEA 2025.3 with a Java 21 toolchain. Kotlin's
language and API versions match the 2.2 standard library in that IDE;
the plugin does not bundle its own standard library.

Use JDK 21 or newer to run Gradle, with a UTF-8 locale (some test names contain
typographic apostrophes). The toolchain resolver downloads JDK 21 if it is missing.
Import the repository as a Gradle project, or run:

```sh
./gradlew check buildPlugin verifyPluginStructure
# File-drop and setup smoke checks with the MCP Server plugin disabled.
./gradlew testWithoutMcp
# Use your installed Rider in a separate development sandbox.
./gradlew runLocalIde -PlocalIdePath="/path/to/Rider.app"
```

The installable zip is in `build/distributions/`. `check` runs the story-core
unit tests (plain JUnit, no IDE) and the IDE-level tests (IntelliJ Platform
test framework), which call `code_story_show` the way the IDE's MCP server
does and check the report, the saved files and the tool window.
They also cover guide/list/get, replacement in local and shared folders, writing
reports, auto-play, playback controls, resume/restart, card dismissal,
gutter navigation, presentation cleanup and the absence of code-line inlays.
Anchoring tests edit fixture documents and check corrections, step statuses, live
markers during playback and the rewrite of local (never shared) stories. Change
information tests build a fixture Git history and check the A/M badges, the saved
base commit, **Show diff** and the unknown-base warning.
File-drop tests create and replace real files and check playback, visible problems,
corrections and delivery markers. `check` also runs these tests in a separate IDE
sandbox with MCP Server disabled. Skill tests install and update both agent copies
in temporary user and project folders; setup tests cover status and first-run guidance.
Management tests cover share/unshare, deletion confirmation, shared arrivals without
playback, repository grouping and separate reader state for duplicate story IDs.
Core tests also cover Markdown export, including text-only steps and escaped paths.
Feedback tests cover listening, timeout, cancellation, settings and repository scope,
post comments in the fixture, call `code_story_feedback` and
`code_story_reply`, and check thread states, withdraw, "already answered", thread
preservation on revision, unsent comments in workspace storage, the details pane and
the card; core tests
cover the thread state machine, the thread format and the answer checks.

The story core (package `io.github.hubertrybak.codestory.core`) has no IDE
dependencies: the story model, the validation rules, the anchor policy, the file
format and the story folder. The plugin compiles against the bundled MCP Server
plugin and uses the kotlinx.serialization library that ships with the IDE; it does
not bundle its own copy.

```sh
# Full compatibility matrix: Rider and IDEA, three releases and latest EAP.
./gradlew verifyPlugin
# One target, useful during development.
./gradlew verifyPlugin -PverificationType=RD -PverificationVersion=2025.3
# Verify against an installed IDE without downloading another copy.
./gradlew verifyPlugin -PlocalIdePath="/path/to/Rider.app"
```

`localIdePath` changes the launch and verification targets only. Compilation
keeps using the 2025.3 SDK, so local development does not raise the minimum
supported IDE version. `./gradlew runIde` launches the baseline IDEA sandbox.

## Release

CI builds the zip, runs `check`, checks the plugin structure, and runs the
Plugin Verifier in eight jobs: Rider and IDEA 2025.3, 2026.1, 2026.2 and the
latest EAP for each product. Reports and the zip are workflow artifacts.

1. Set `version` in `gradle.properties` and in
   [`skills/code-story/version.txt`](skills/code-story/version.txt).
2. Add an entry for the version, with the release date, to [CHANGELOG.md](CHANGELOG.md).
3. Complete the [manual checklist](docs/release-checklist.md). For 1.0.0, also record the
   v1 done criteria in [docs/v1-verification.md](docs/v1-verification.md).
4. Push a matching tag, for example `v1.0.0`.

The tag build fails when the tag does not match `version` or when CHANGELOG.md has no
entry for the version. After every build and verifier job passes, the workflow publishes
the verified build's zip to GitHub Releases, with the CHANGELOG entry above the generated
notes. Release publication uses the workflow's `GITHUB_TOKEN`; no Marketplace
credentials are needed.

Licensed under [Apache-2.0](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md).
