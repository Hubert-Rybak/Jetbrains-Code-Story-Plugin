# Code Story

A JetBrains plugin for guided code walkthroughs, with Rider as the primary
target. Requires Rider or IntelliJ IDEA 2025.3 or newer.

An AI agent builds a story with a type, summary, original request, author and
ordered steps, and sends it to the running IDE through the IDE's MCP server.
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

The MCP Server plugin is optional, but agents need it to send stories. The
Git plugin is optional; it supplies branch and commit metadata when available.

## Use

1. In the IDE, open **Settings | Tools | MCP Server**, switch on the MCP
   server, and use **Auto-Configure** for your agent.
2. Install the `code-story` skill by hand: copy the
   [`skills/code-story`](skills/code-story) folder into the skills folder
   that your agent reads, for example `~/.agents/skills/` for Codex CLI and
   other agents that read `.agents/skills/`.
3. Open the project in the IDE, and ask your agent:
   `/code-story "Explain the main change for this ticket"` (`$code-story` in Codex).

The agent calls the `code_story_show` MCP tool with the story and the
`projectPath` of the project. The IDE checks the story against the project
files. A valid story is saved in `.codestory/local/` at the Git repository
root (the project folder when there is no Git) and appears in the tool window.
It starts at step 1 and brings the IDE to the front. Turn off bring-to-front
or change the range highlight color in **Settings | Tools | Code Story**.
`.codestory/local/` has its own `.gitignore`, so local stories stay out of Git.
A story with errors is not saved: the tool returns each error with the step
number, a code, a message and a fix hint, so the agent can correct the story.

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
# Use your installed Rider in a separate development sandbox.
./gradlew runLocalIde -PlocalIdePath="/path/to/Rider.app"
```

The installable zip is in `build/distributions/`. `check` runs the story-core
unit tests (plain JUnit, no IDE) and the IDE-level tests (IntelliJ Platform
test framework), which call `code_story_show` the way the IDE's MCP server
does and check the report, the saved files and the tool window.
They also cover auto-play, playback controls, resume/restart, card dismissal,
gutter navigation, presentation cleanup and the absence of code-line inlays.

The story core (package `io.github.hubertrybak.codestory.core`) has no IDE
dependencies: the story model, the validation rules, the file format and the
story folder. The plugin compiles against the bundled MCP Server plugin and
uses the kotlinx.serialization library that ships with the IDE; it does not
bundle its own copy.

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
