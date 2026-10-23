# Contributing to Code Story

Report bugs and ideas in the
[issue tracker](https://github.com/Hubert-Rybak/Jetbrains-Code-Story-Plugin/issues). This
page explains how to build, test and release the plugin.

- [Build and run](#build-and-run)
- [Tests](#tests)
- [Compatibility checks](#compatibility-checks)
- [Code structure](#code-structure)
- [Continuous integration](#continuous-integration)
- [Release](#release)

## Build and run

This single-module Kotlin project is based on the
[official IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template/tree/b7b700870b23e1323353a0350a1acd9cc6d2b225).
It compiles against IntelliJ IDEA 2025.3 with a Java 21 toolchain. Kotlin's language and
API versions match the 2.2 standard library in that IDE; the plugin does not bundle its
own standard library.

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

The installable zip is in `build/distributions/`. `./gradlew runIde` launches the
baseline IDEA sandbox. To run it without a screen, for example in a container, or to take
the screenshots of the README, see
[Run the plugin in a sandbox IDE](CLAUDE.md#run-the-plugin-in-a-sandbox-ide). The helpers
and a demo project are in [`tools/ide-sandbox`](tools/ide-sandbox).

## Tests

`check` runs the story-core unit tests (plain JUnit, no IDE) and the IDE-level tests
(IntelliJ Platform test framework). The IDE-level tests call `code_story_show` the way
the IDE's MCP server does, and check the report, the saved files and the tool window.

| Area | What the tests cover |
|---|---|
| Agent tools | Guide, list and get, replacement in local and shared folders, and writing reports. |
| Playback | Auto-play, playback controls, resume and restart, card dismissal, gutter navigation, presentation cleanup and the absence of code-line inlays. |
| Timeline | Step blocks for every kind, code with file line numbers, without shared indentation and with proposal diffs, navigation from a code line without a step card, selections that a click keeps for copying, following the player and edits, view switching with the current step in view, a scroll position that reloads keep, the remembered view, comments and HTML-safe paths. |
| Step kinds | Core tests for the format, validation, patches, revisions, Markdown export and the proposal diff, and agent API tests from a proposal to its applied code. |
| Anchoring | The tests edit fixture documents and check corrections, step statuses, live markers during playback and the rewrite of local (never shared) stories. |
| Change information | The tests build a fixture Git history and check the A/M badges, the saved base commit, **Show diff** and the unknown-base warning. |
| File drops | The tests create and replace real files and check playback, visible problems, corrections and delivery markers. `check` also runs these tests in a separate IDE sandbox with MCP Server disabled. |
| Skill and setup | Skill tests install and update both agent copies in temporary user and project folders. Setup tests cover status and first-run guidance. |
| Story management | Share and unshare, deletion confirmation, shared arrivals without playback, repository grouping and separate reader state for duplicate story IDs. |
| Markdown export | Core tests, including text-only steps and escaped paths. |
| Feedback | Listening, timeout, cancellation, settings and repository scope. The tests post comments in the fixture, call `code_story_feedback` and `code_story_reply`, and check thread states, withdraw, "already answered", thread preservation on revision, unsent comments in workspace storage, the details pane and the card. Core tests cover the thread state machine, the thread format and the answer checks. |

## Compatibility checks

```sh
# Full compatibility matrix: Rider and IDEA, three releases and latest EAP.
./gradlew verifyPlugin
# One target, useful during development.
./gradlew verifyPlugin -PverificationType=RD -PverificationVersion=2025.3
# Verify against an installed IDE without downloading another copy.
./gradlew verifyPlugin -PlocalIdePath="/path/to/Rider.app"
```

`localIdePath` changes the launch and verification targets only. Compilation keeps using
the 2025.3 SDK, so local development does not raise the minimum supported IDE version.

## Code structure

The story core (package `io.github.hubertrybak.codestory.core`) has no IDE dependencies:
the story model, the validation rules, the anchor policy, the file format and the story
folder. The plugin compiles against the bundled MCP Server plugin and uses the
kotlinx.serialization library that ships with the IDE; it does not bundle its own copy.

The agent skill is in [`skills/code-story`](skills/code-story), and the JSON Schema of
the story format is in [`schema`](schema). The build packages the skill and the writing
guide into the plugin.

## Continuous integration

CI builds the zip, runs `check`, checks the plugin structure, and runs the Plugin
Verifier in eight jobs: Rider and IDEA 2025.3, 2026.1, 2026.2 and the latest EAP for
each product. Reports and the zip are workflow artifacts.

## Release

1. Set `version` in `gradle.properties` and in
   [`skills/code-story/version.txt`](skills/code-story/version.txt).
2. Add an entry for the version, with the release date, to [CHANGELOG.md](CHANGELOG.md).
   This entry is also the plugin's change notes in **Settings | Plugins** and on JetBrains
   Marketplace.
3. Complete the [manual checklist](docs/release-checklist.md). For 1.0.0, also record the
   v1 done criteria in [docs/v1-verification.md](docs/v1-verification.md).
4. Push a matching tag, for example `v1.0.0`.

The tag build fails when the tag does not match `version` or when CHANGELOG.md has no
entry for the version. After every build and verifier job passes, the workflow publishes
the verified build's zip to GitHub Releases, with the CHANGELOG entry above the generated
notes. The GitHub release uses the workflow's `GITHUB_TOKEN`.

The tag also publishes the plugin to JetBrains Marketplace: the `marketplace` job builds
the tagged commit and runs `./gradlew publishPlugin`. The first version goes to
Marketplace by hand, as the [checklist](docs/release-checklist.md#jetbrains-marketplace)
describes. These repository secrets (**Settings | Secrets and variables | Actions**)
control the job:

| Secret | Value |
| --- | --- |
| `MARKETPLACE_API_KEY` | A token from [My Tokens](https://plugins.jetbrains.com/author/me/tokens) on JetBrains Marketplace. Without it, the job does not publish. |
| `CERTIFICATE_CHAIN` | Optional: the content of `chain.crt` from the [signing key](docs/release-checklist.md#signing-key) steps. |
| `PRIVATE_KEY` | Optional: the content of `private_encrypted.pem`. |
| `PRIVATE_KEY_PASSWORD` | Optional: the password of the private key. |

With `CERTIFICATE_CHAIN` and `PRIVATE_KEY`, `signPlugin` signs the zip before the upload.
Without them, the job uploads the unsigned zip. To publish from your machine, set the token as
`PUBLISH_TOKEN`, the signing values under the same names, and run `./gradlew publishPlugin`.
