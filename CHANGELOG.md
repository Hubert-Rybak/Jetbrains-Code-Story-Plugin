# Changelog

All notable changes to Code Story are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Default-on automatic feedback listening through `code_story_listen`. Connected agents
  pick up Submit Round, answer, and listen again without another chat prompt.
- A setting to disable automatic listening, with cancellation and manual feedback fallback.

### Changed

- Restrict the plugin to Rider, with Rider 2026.1 (RD-261.22158.335) as the compilation
  baseline and descriptor minimum; compile Java 21 bytecode with Kotlin language/API 2.3.
- Pin all core/IDE behavioral tests and the single Plugin Verifier target to Rider
  2026.2.3.1 (RD-262.10968.170), running on Java 25. Keep the MCP-disabled file-drop
  smoke suite on the same current runtime, without repeating the full baseline suite.
- Replace the IDEA/older-Rider/EAP CI matrix with one verified build and a dependent,
  tag-only release of the same ZIP. Add lightweight policy contracts, job timeouts
  and bounded report/artifact retention.
- Allow exact-version/build-validated local Rider distributions for the compilation
  SDK and current tests, development sandbox and verifier, avoiding duplicate downloads.

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

[1.0.0]: https://github.com/Hubert-Rybak/Jetbrains-Code-Story-Plugin/releases/tag/v1.0.0
