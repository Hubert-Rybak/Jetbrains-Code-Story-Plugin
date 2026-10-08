# Code Story

A JetBrains plugin for guided code walkthroughs, with Rider as the primary
target. Requires Rider or IntelliJ IDEA 2025.3 or newer.

An AI agent builds a story with a type, summary, original request, author and
ordered steps, and sends it through the IDE's MCP server or drops a story file into the project.
The story starts in the receiving IDE, with a floating comment card beside
the highlighted code. The **Code Story** tool window lists stories newest first
and shows their details, including who wrote them and the Git branch and
commit at creation. It holds the card when the editor has no room for it.
Text-only steps show their comment in the details pane and leave the editor
as it is. See
[spec.md](spec.md) for the product specification and
[issue #1](https://github.com/Hubert-Rybak/Jetbrains-Code-Story-Plugin/issues/1)
for the implementation roadmap.

## Install

Download the plugin zip from [GitHub Releases](https://github.com/Hubert-Rybak/Jetbrains-Code-Story-Plugin/releases).
In the IDE, open **Settings | Plugins | gear icon | Install Plugin from Disk**,
select the zip, and restart if prompted. Open **View | Tool Windows | Code Story**.
The window starts on the right; use the IDE's normal tool window controls to move it.

The MCP Server plugin is optional: without it, agents deliver stories as files. The
Git plugin is optional; it supplies branch and commit metadata and change
information when available.

## Use

1. Choose **Tools | Code Story | Install Code Story skill** (also in the tool-window
   toolbar). This installs the bundled skill into both `~/.agents/skills/code-story/`
   and `~/.claude/skills/code-story/`. To install into the project instead, enable
   that option in **Settings | Tools | Code Story**, then run the action.
2. For direct delivery and validation reports, open **Settings | Tools | MCP Server**,
   switch on the MCP server, and use **Auto-Configure** for your agent.
3. Open the project in the IDE, and ask your agent:
   `/code-story "Explain the main change for this ticket"` (`$code-story` in Codex).

The first run shows one notification with these setup steps. Code Story opens the
MCP settings when you choose its notification action; it never enables the server
itself. **Tools | Code Story | Check setup** reports the server state, registered
Code Story tools, and installed skill paths and versions. After a plugin update,
the plugin offers to update older installed skills. You can also install manually
by copying [`skills/code-story`](skills/code-story) to either agent skills folder.

The agent first calls `code_story_guide` for the writing rules, format and example
bundled with the installed plugin version. It calls `code_story_show` with the story and the
`projectPath` of the project. The IDE checks the story against the project
files. A valid story is saved in `.codestory/local/` at the Git repository
root (the project folder when there is no Git) and appears in the tool window.
It starts at step 1 and brings the IDE to the front. Turn off bring-to-front
or change the range highlight color in **Settings | Tools | Code Story**.
`.codestory/local/` has its own `.gitignore`, so local stories stay out of Git.
A story with errors is not saved: the tool returns each error with the step
number, a code, a message and a fix hint, so the agent can correct the story.

When the tools are missing or the target project is not open, the skill writes a
complete story to `.codestory/local/`, using a temporary file and then a rename.
The plugin watches `.codestory/` only, including files dropped before the project
opens. It checks new and changed local stories against the same location rules and
unsaved editor text as MCP. Errors appear in the tool window and clear when the
file is fixed or removed. Valid drops play in every IDE that has the project open.
MCP-delivered stories carry `"delivery": "mcp"`, so other IDEs list them without
auto-playing them. Shared stories refresh the list without auto-play.

Each step location also carries `anchorText`, the exact text of its first line.
The IDE compares it with the line that it shows, including unsaved edits;
indentation, trailing spaces and line endings do not matter. When the text is
within 20 lines of the given line, the IDE moves the range and the report lists
a correction (for example `Step 4: lines 120–131 changed to 123–134.`). When it
cannot find the text, it rejects the story. The plugin stores the code of each
range in the story.

While a story plays, live range markers follow your edits, so the card and the
highlight stay on the right code. When a story is listed or played, the plugin
finds the code of each step again. A step is **ok**; **moved** (the lines are
updated); **code changed** (the range has other code than the step explains:
the tree shows a badge and the card a note); or **lost** (the code is gone: the
step plays at its stored lines, and the card warns "The code changed. This step
can be wrong."). The story row shows the number of lost and changed steps, and
`code_story_list` reports the status. Moved lines are saved in local stories
only; shared story files never change, and their corrections stay in memory.

A story about a change can set `baseRef`: the commit before the change, or
`HEAD` for uncommitted work. The plugin saves the full SHA of that commit, so the
base stays the same after the work is committed. Each step then shows **A** in
the tree when all of its lines are new since the base, or **M** when some of them
changed; steps without changes show no badge. **Show diff**, in the step details
and the context menu, opens the IDE diff viewer: the file at the base commit
against the current file, scrolled to the step's lines. A renamed file is compared
with its old path. A `baseRef` that Git does not know gives an `unknown-base-ref`
warning in the report, and the story then shows no change information, like a
story without a base. Without the Git plugin, change information is off.

`code_story_list` lists local and shared stories with their ID, title, type, date,
branch, repository path, folder and status: `ok`, `moved`, `code-changed` or `lost`, with the number
of moved, changed and lost steps. `code_story_get(storyId)` returns the full saved
story. To revise it, the agent calls `code_story_show(story, storyId)`;
the same file and creation metadata are kept. A playing story reloads at the same
step number, or the last step if the revision is shorter.

The tree groups stories under **Local** and **Shared**. **Share** moves a story
to `.codestory/shared/` so you can commit it with the branch; **Unshare** moves it
back. Its ID and creation metadata stay the same. **Delete** asks for confirmation
before removing the file. These actions are in the toolbar and context menu.
**Copy as Markdown**, in both menus, copies the title, summary, and steps with
repository-relative line links and comments for a pull request description.
Reader threads are not included.

Shared stories that arrive through checkout or pull appear automatically with a
**new** marker. They do not start playback. Playing one clears the marker in your
IDE workspace, without changing the shared file.

With several Git repositories mapped in one project, the tree groups by repository
and each repository has its own `.codestory/`. A story must stay inside one
repository. Pass `repositoryPath` (the absolute repository root) to
`code_story_show` when paths match several repositories or the story is text-only.
The same parameter selects the repository for `code_story_get` and replacements
when an ID appears in several repositories; `code_story_list` returns it for each story.

The report also includes writing warnings and warnings for ranges longer than
30 lines. They never block a story and appear only in the agent report. The guide
asks the agent to fix them once and replace the story with its returned ID.
Mechanical checks cover title, sentence and comment lengths, list structure,
semicolons and contractions. Code names count as one word. Optional passive voice
and -ing hints are off by default; enable them in **Settings | Tools | Code Story**.

Story files are named `<yyyy-MM-dd>-<slug>.story.json`. Their format is
described by the JSON Schema in [`schema/story-v1.schema.json`](schema/story-v1.schema.json).
Paths are relative to the repository root; line numbers are 1-based and inclusive.
The plugin records creation time and Git state, assigns stable IDs to steps
that have none, and adds a number suffix when a story name already exists.
The story details show a note when the current branch differs from the branch
where the story was created. Without Git, the metadata is shown as unavailable.

Use **Prev**, **Next** and **Stop** in the card or tool-window toolbar. On the
last step, **Next** becomes **Finish**. Numbered gutter badges jump to other
steps in the same file. Closing the card keeps your place and the highlighted
code; click the current badge or step to reopen it. Scrolling and switching
tabs hide and restore the card without moving your caret or scrolling back.

**Play** resumes the selected story at your last step. Right-click the story
and choose **Restart** to start at step 1. Reading positions stay in your IDE
workspace, and visited marks last for the current session.

The default shortcuts start with **Ctrl+Alt+Shift+Z**; release those keys, then
press **Right** for Next Step, **Left** for Previous Step, or **End** for Stop
Story. Change them in **Settings | Keymap** by searching for the action name.
Comments support inline code, bold, bullet lists and links to http/https URLs
or repository files (`src/App.kt#L2-L5`). Links open only when clicked.

## Develop

This single-module Kotlin project is based on the
[official IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template/tree/b7b700870b23e1323353a0350a1acd9cc6d2b225).
It compiles against IntelliJ IDEA 2025.3 with a Java 21 toolchain. Kotlin's
language and API versions match the 2.2 standard library in that IDE;
the plugin does not bundle its own standard library.

Use JDK 21 or newer to run Gradle. The toolchain resolver downloads JDK 21
if it is missing. Import the repository as a Gradle project, or run:

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

Set `version` in `gradle.properties`, complete the
[manual checklist](docs/release-checklist.md), and push a matching tag
(for example, `v0.1.0`). The same workflow publishes the verified build's zip
to GitHub Releases after every build and verifier job passes. A tag that
does not match the configured version fails the build. Release publication
uses the workflow's `GITHUB_TOKEN`; no Marketplace credentials are needed.

Licensed under [Apache-2.0](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md).
