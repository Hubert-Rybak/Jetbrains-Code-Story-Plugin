# Changelog

All notable changes to Code Story are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- The **Code Story Timeline** tool window shows a whole story at once: every step from top
  to bottom on a line of numbered steps, with the agent's explanation and the code of the
  step in a read-only editor with the colors and line numbers of its file. A click on a
  line of code opens that line in the editor beside the timeline, with the step's highlight
  and gutter badges and without a step card. The timeline marks the current step, follows
  **Prev**, **Next** and the gutter badges, shows the code as you edit it, and has the
  comment threads and a comment input for the current step. Open it with **Open in
  Timeline** in the Code Story tool window, the timeline button in the step card, or
  **Ctrl+Alt+Shift+Z**, then **T**. **Play with Step Card** switches back.
- Step kinds for agents: `context` steps give background for the step before them, and
  `proposal` steps suggest a change, with optional `proposedCode` for the lines of the step.
  The timeline shows context under its step and a proposal as a change of the current
  code. **Preview change** opens the IDE diff viewer, and **Ask agent to apply** posts a
  change request on the step. The writing guide, the skill, the MCP tool schemas and the
  JSON Schema describe the kinds; story files omit the kind of other steps, so existing
  stories do not change.
- New stories open in the view that you used last, the step card or the timeline. You can
  also choose it in **Settings | Tools | Code Story**.

### Changed

- The plugin no longer uses IntelliJ Platform API that is deprecated or experimental in
  the supported IDE versions, so the Plugin Verifier reports no such usages.
- The step card, the tree and the details pane name context and proposal steps. A
  proposal's card has **Preview change**, and **Copy as Markdown** puts context under its
  step and a proposal's code in a `diff` block.

## [1.1.1] - 2026-10-21

### Fixed

- The story details and the file problems in the **Code Story** tool window use the IDE's
  UI font, like the rest of the tool window, instead of the text area font.
- The step card no longer moves into the **Code Story** tool window when the tool window
  or the IDE is resized. Without room beside the code, it floats below or above the step,
  or over the code at the editor's right edge. Only an editor narrower than the card still
  shows it in the tool window.

## [1.1.0] - 2026-10-18

### Added

- A plugin logo for **Settings | Plugins** and the README, with a version for dark
  themes.
- Change notes in **Settings | Plugins**: the CHANGELOG.md entry of the installed version.
- A version tag also publishes the plugin to JetBrains Marketplace when the repository
  has a Marketplace token, and signs it when the repository has a signing key.

### Changed

- Post comments at once: **Post** (Ctrl+Enter) writes a question or change request into
  the story, and a listening agent picks it up immediately. Drafts and **Submit Round**
  are gone; comments posted before an agent fetches them still reach it together.
  **Withdraw** opens a waiting comment for editing. A withdrawn comment, or one that could
  not be written, stays in the IDE workspace as an unsent comment with **Post**, **Edit**
  and **Delete**.
- The step card is larger. Its comment input and playback buttons stay in view while the
  step text and the comments scroll, and it shows the threads of its step directly. The
  details pane also keeps its comment input below the scrolling details.
- Resize the floating card by its right edge, its bottom edge or its corner grip. Every
  card keeps that size; a double-click on the grip restores the default size. Without
  room for that size beside the code, the card gets narrower before it moves to the tool
  window.
- Smaller, smoother gutter step badges that match the gutter. The current step is filled
  with the accent color, and other steps are outlined in the line number color.
- The plugin description in **Settings | Plugins** starts with a short summary, lists the
  main features, and links to the setup instructions and the issue tracker.
- A shorter README with a quick start and one setup that works for every agent. The full
  documentation is in a user guide, an agent API reference and a contributing guide.

## [1.0.0] - 2026-10-10

The first release. Install the zip from disk in Rider or IntelliJ IDEA 2025.3 or newer.
The [README](https://github.com/Hubert-Rybak/Jetbrains-Code-Story-Plugin#readme)
explains the setup for Claude Code, Codex CLI, GitHub Copilot in Rider and GitHub
Copilot CLI.

### Added

- Agent API in the IDE's MCP server: `code_story_guide`, `code_story_show`,
  `code_story_list`, `code_story_get`, `code_story_patch`, `code_story_feedback` and
  `code_story_reply`, with typed parameters and reports of errors, corrections and
  warnings with fix hints.
- The `code-story` agent skill, bundled with the plugin: `/code-story "<request>"`
  (`$code-story` in Codex) and `/code-story feedback`.
- File-drop delivery: without the MCP server, the agent writes the story file into
  `.codestory/local/`, and the plugin validates and plays it.
- Story format version 1 with a published JSON Schema: change, exploration, bug-trace
  and review stories with 15 steps at most, text-only steps, author, creation time, Git
  branch and commit, and an optional `baseRef`.
- Floating step card beside the highlighted code, with a tool-window fallback when the
  editor has no room, and numbered gutter badges for the other steps in the file.
- Playback with **Prev**, **Next**, **Finish** and **Stop** in the card, the tool window
  and keyboard shortcuts; **Play** resumes at the last step and **Restart** starts again.
- The **Code Story** tool window: local and shared stories, grouped by repository when a
  project has several, and a details pane with the summary, request, author, branch,
  commit and diff base.
- Anchoring: line corrections on arrival, live range markers during playback, and the
  step statuses ok, moved, code changed and lost.
- Change information for stories with a `baseRef`: **A** and **M** badges and
  **Show diff**.
- **Share**, **Unshare**, **Delete** with confirmation, a **new** marker for shared
  stories that arrive through Git, and **Copy as Markdown**.
- Writing checks based on ASD-STE100 rules, reported to the agent as warnings.
- Reader feedback loop: questions and change requests as drafts, rounds, answers in
  threads, **Request refresh**, unread markers, and a check that rejects `done` while
  affected steps are not updated or confirmed.
- Optional feedback hook snippets for Claude Code, Codex and Copilot CLI, with a
  pending-feedback index in `.codestory/local/`.
- Setup: a first-run notification, **Install Code Story skill** with update offers,
  **Check setup**, and **Settings | Tools | Code Story**.
- CI runs the tests and the Plugin Verifier for Rider and IntelliJ IDEA 2025.3, 2026.1,
  2026.2 and the latest EAP; a version tag publishes the zip to GitHub Releases.
- Default-on automatic feedback listening through `code_story_listen`. Connected agents
  pick up Submit Round, answer, and listen again without another chat prompt.
- A setting to disable automatic listening, with cancellation and manual feedback fallback.

### Fixed

- Remember accepted file drops across project restarts, preserving saved code baselines
  and showing changed or missing source as stale instead of rejecting the story.
- Keep local and shared files with the same ID separate in reader state and comment
  updates; reject ambiguous agent requests instead of choosing a file.
- Reject source paths that escape the repository through symlinks during playback and
  diff viewing, including paths that changed after story validation.
- Return validation errors for overflowing line ranges without stopping file watching.
- Keep newly received answers when navigating with gutter badges, and retain drafts,
  selection and reading position when sharing or unsharing a story.

### Changed

- Resolve playback anchors and read source files in the background. Stopping or changing
  stories cancels obsolete work, and edits made during a search trigger a fresh check.

[1.1.1]: https://github.com/Hubert-Rybak/Jetbrains-Code-Story-Plugin/releases/tag/v1.1.1
[1.1.0]: https://github.com/Hubert-Rybak/Jetbrains-Code-Story-Plugin/releases/tag/v1.1.0
[1.0.0]: https://github.com/Hubert-Rybak/Jetbrains-Code-Story-Plugin/releases/tag/v1.0.0
