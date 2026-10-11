# Code Story v1 verification

> Historical record from before the Rider-only SDK/CI migration. Versions, criteria
> and executed results below describe that earlier configuration and are preserved,
> not evidence of a successful current-Rider test run. Current support and release
> gates are in the [README](../README.md#develop) and
> [release checklist](release-checklist.md); the migrated JVM build, behavioral tests
> and single verifier still require real execution. Billing-blocked CI is not green.

This record checks Code Story 1.0.0 against the nine "v1 is done when" criteria at the
end of [spec.md](../spec.md#further-notes). Automated results come from the test suite and
CI. The other checks need the IDEs, the agents and their accounts: do the steps, then
write the date, the versions, the result and any notes into the tables. Tag `v1.0.0` only
when every criterion passes. A failed check needs a fix (or an agreed change of the
criterion) and a new run of the affected checks.

## Summary

| # | Done criterion | How it is checked | Result |
|---|---|---|---|
| 1 | Claude Code in Rider 2026.2 on a real C# solution creates a story through MCP; it plays, and every step points at the right code. | Manual ([1](#1-claude-code-in-rider-20262)) | Pending |
| 2 | The same flow works with Codex CLI, GitHub Copilot in Rider and Copilot CLI. | Manual ([2](#2-codex-cli-copilot-in-rider-and-copilot-cli)) | Pending |
| 3 | With the MCP server off, the file-drop fallback works. | Tests and manual ([3](#3-file-drop-with-the-mcp-server-off)) | Tests passed; manual pending |
| 4 | In the usual case, the agent fixes a story from the report's errors and warnings without help. | Manual, over the runs of 1 and 2 ([4](#4-agents-fix-stories-from-the-report)) | Pending |
| 5 | A story that is shared, committed and checked out in a second clone plays there. | Tests and manual ([5](#5-a-shared-story-in-a-second-clone)) | Tests passed; manual pending |
| 6 | Editing code during playback keeps the anchors right; after large edits, lost steps show a warning. | Tests and manual ([6](#6-drift-during-editing)) | Tests passed; manual pending |
| 7 | The Plugin Verifier passes for Rider and IntelliJ IDEA from 2025.3 to the current EAP, and the tests pass in CI. | CI ([7](#7-plugin-verifier-and-tests-in-ci)) | Passed on `main` at `4a3760b`; pending for the release commit |
| 8 | The manual checklist passes in Rider (C#) and IntelliJ IDEA (Java/Kotlin), in light and dark themes. | Manual ([8](#8-manual-checklist)) | Pending |
| 9 | A feedback round answers questions and applies a change request; "done" is rejected until the affected steps are updated or confirmed. | Tests and manual ([9](#9-a-feedback-round)) | Tests passed; manual pending |

## Environment of the manual checks

| Item | Version or name |
|---|---|
| Code Story zip (file name and SHA-256) | |
| Rider | |
| IntelliJ IDEA | |
| Operating system | |
| Claude Code | |
| Codex CLI | |
| GitHub Copilot plugin in Rider | |
| GitHub Copilot CLI | |
| C# solution (repository, size) | |
| Java or Kotlin project | |

Use a fresh IDE profile for the install checks. Record the exact IDE builds from
**Help | About**.

## Automated results

### Local run

2026-10-10, branch `feature/v1-release` (version 1.0.0), Linux, JDK 21.0.12, Gradle 9.8.0,
test platform IntelliJ IDEA 2025.3 (build 253.28294.334),
`./gradlew clean check buildPlugin verifyPluginStructure`:

| Task | Result |
|---|---|
| `test`: story-core unit tests and IDE-level tests | Passed: 424 tests in 29 classes, no failures |
| `testWithoutMcp`: file-drop and setup tests with the MCP Server plugin disabled | Passed: 17 tests, no failures |
| `buildPlugin`, `verifyPluginStructure` | Passed: `code-story-1.0.0.zip`, with version 1.0.0 in `plugin.xml`, the guide and the bundled skill |

The README's feedback hook helper is the same as the bundled one. With a pending-feedback
index, it printed the reminder in the Claude Code, Codex and Copilot CLI formats; without
pending feedback, it printed `{}`.

### CI

[Run 38056627346](https://github.com/Hubert-Rybak/Jetbrains-Code-Story-Plugin/actions/runs/38056627346)
on `main` at `4a3760b` (all v1 features, version 0.1.0): the build job (`check`,
`buildPlugin`, `verifyPluginStructure`) and all eight Plugin Verifier jobs passed. The
verifier found the plugin compatible with IntelliJ IDEA and Rider 2025.3, 2026.1 and 2026.2,
and with the EAP builds IU-263.6259.32 and RD-263.6259.33. Its only notes are deprecated
and experimental `ToolWindowFactory` default methods, which the Kotlin compiler bridges in
`CodeStoryToolWindowFactory`.

Release commit: link the CI run of the `v1.0.0` tag here, and the run of the merge commit
on `main` when it differs.

### Tests that cover the criteria

The IDE-level tests call the agent tools the way the IDE's MCP server calls them, in a
fixture project. They cannot show a real agent, the real MCP transport, Rider's C#
editor or the themes; the manual checks cover these.

| # | Tests | What they show |
|---|---|---|
| 1, 2 | `CodeStoryShowToolTest`, `CodeStoryAgentToolsTest` | The toolset is registered in the MCP server, the tools have typed parameters, an accepted story is saved and plays from step 1, and every tool needs an open project. |
| 3 | `StoryFileDropTest`, also in `testWithoutMcp` | Valid drops play without MCP, also when they wait for the project to open; invalid drops show errors in the tool window, and fixing or deleting the file clears them. |
| 4 | `StoryValidatorTest`, `CodeStoryShowToolTest`, `CodeStoryAgentToolsTest` | Every error, correction and warning has a step number, a code, a message and a fix hint; a rejected story saves nothing. |
| 5 | `StoryManagementTest` | Share and Unshare keep the story; a shared file that arrives through Git is listed as new without auto-play. |
| 6 | `StoryAnchoringTest`, `AnchorPolicyTest` | Live markers follow edits during playback; moved, changed and lost steps are found; a lost step plays at its stored lines with the warning. |
| 9 | `CodeStoryFeedbackToolsTest`, `StoryFeedbackTest`, `StoryThreadsTest` | A round is fetched and answered; patches keep threads and add the story changes; "done" is rejected until every affected step is updated, replaced or confirmed. |

## Manual checks

Install the release candidate from disk: `code-story-1.0.0.zip` from the
`plugin-distribution` artifact of the CI run, or from `build/distributions/`. Install the skill with **Tools | Code Story |
Install Code Story skill**, and switch on **Settings | Tools | MCP Server** (see
[Set up your agent](../README.md#set-up-your-agent)). Ask for real subjects: a recent
change, a flow through several layers, and a bug path.

### 1. Claude Code in Rider 2026.2

1. Open a real C# solution in Rider 2026.2, and connect Claude Code with
   **Auto-Configure**.
2. In the solution folder, run `/code-story "<request>"` for a recent change, and again
   for a flow through several layers.
3. Check that the story file in `.codestory/local/` has `"delivery": "mcp"`, that the story
   plays from step 1, and that every step opens the right file and range.

| Date | Request | Story ID | Steps | Every step on the right code | Fix rounds | Result | Notes |
|---|---|---|---|---|---|---|---|
| | | | | | | | |

### 2. Codex CLI, Copilot in Rider and Copilot CLI

Repeat check 1 with each agent: `$code-story "<request>"` in Codex CLI,
`/code-story "<request>"` in Copilot Chat (Agent mode) in Rider, and
`/code-story "<request>"` in Copilot CLI. Note how each agent was connected
(Auto-Configure, or the manual configuration from the README).

| Agent | Date | Connection | Story ID | Steps | Every step on the right code | Fix rounds | Result | Notes |
|---|---|---|---|---|---|---|---|---|
| Codex CLI | | | | | | | | |
| GitHub Copilot in Rider | | | | | | | | |
| GitHub Copilot CLI | | | | | | | | |

### 3. File drop with the MCP server off

1. Switch off the MCP server (or disable the MCP Server plugin) and restart the agent.
2. Run `/code-story "<request>"`. The agent writes the file through the fallback, and the
   story appears and plays.
3. Break a line number in the file: the tool window shows the error. Fix or delete the
   file: the error clears.
4. Close the project, drop a valid story, and open the project: the story plays.

| Date | Agent | IDE | Result | Notes |
|---|---|---|---|---|
| | | | | |

### 4. Agents fix stories from the report

Count over all runs of checks 1 and 2. "Usual case" means that most stories are accepted
at once or after one fix round, and that no story needs help from the developer.

| Agent | Stories | Accepted at once | Accepted after fix rounds | Needed help | Notes |
|---|---|---|---|---|---|
| Claude Code | | | | | |
| Codex CLI | | | | | |
| GitHub Copilot in Rider | | | | | |
| GitHub Copilot CLI | | | | | |

### 5. A shared story in a second clone

1. **Share** a story, commit `.codestory/shared/` and push the branch.
2. In a second clone that is open in the IDE, check out the branch.
3. The story appears with the **new** marker and does not start by itself. **Play** plays
   it, and every step points at the right code.

| Date | IDE | Result | Notes |
|---|---|---|---|
| | | | |

### 6. Drift during editing

1. While a story plays, add and remove lines above the current and later steps. Prev and
   Next open the right code; the card and the highlight stay on the range.
2. Change the code of a later step: the step shows **code changed**.
3. Delete the code of a step: the step shows **lost**, plays at its stored lines, and the
   card shows "The code changed. This step can be wrong."

| Date | IDE and project | Result | Notes |
|---|---|---|---|
| | | | |

### 7. Plugin Verifier and tests in CI

The build job and the eight verifier jobs must pass for the commit that gets the tag.
The release job runs only after all of them pass.

| Commit | CI run | Build and tests | Verifier (8 jobs) | Result |
|---|---|---|---|---|
| `4a3760b` (`main`, before the release changes) | [38056627346](https://github.com/Hubert-Rybak/Jetbrains-Code-Story-Plugin/actions/runs/38056627346) | Passed | Passed | Passed |
| Release commit (`v1.0.0`) | | | | |

### 8. Manual checklist

Complete [release-checklist.md](release-checklist.md) in each configuration.

| IDE | Project | Theme | Date | Result | Notes |
|---|---|---|---|---|---|
| Rider | C# solution | Light | | | |
| Rider | C# solution | Dark | | | |
| IntelliJ IDEA | Java or Kotlin project | Light | | | |
| IntelliJ IDEA | Java or Kotlin project | Dark | | | |

### 9. A feedback round

1. In a story about code that the agent may change, comment on two steps with questions
   and on one step with a change request. **Submit Round**.
2. Tell the agent `/code-story feedback`. The comments show "Agent working".
3. The agent answers the questions in the threads and applies the change request.
4. Before the agent updates the affected steps, its `done` reply is rejected with a list
   of steps (`stale-steps`). After it updates or confirms them, `done` is accepted.
5. The answers appear in the story with a notification, and the reply of the change
   request lists the changed files and the story changes.

| Date | Agent | Questions answered | Change applied | `done` rejected before the step sync | `done` accepted after it | Result | Notes |
|---|---|---|---|---|---|---|---|
| | | | | | | | |

An agent that follows the skill updates the steps before it replies, so the rejection
does not always appear by itself. To see it, ask the agent to reply before it updates the
steps, or edit the code of an affected step after the agent's update and before its reply.
