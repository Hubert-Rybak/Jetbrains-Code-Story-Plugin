# Code Story agent API

Code Story adds eight tools to the IDE's MCP server. The bundled
[`code-story` skill](../skills/code-story/SKILL.md) tells agents when to call them, and
`code_story_guide` returns the [writing guide](../skills/code-story/references/guide.md)
of the installed plugin version. This page is a reference for people who build, check or
debug agent integrations. For the reader's side, see the [user guide](user-guide.md).

- [MCP tools](#mcp-tools)
- [Story validation](#story-validation)
- [Step kinds](#step-kinds)
- [Patch operations](#patch-operations)
- [Story files](#story-files)

## MCP tools

Each call needs the `projectPath` of an open project, and each result names the agent's
next call. `repositoryPath`, the absolute repository root, selects the repository when
paths or story IDs match several repositories, or for a text-only story.

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

The last three tools carry the reader's questions and change requests.
[Questions and change requests](user-guide.md#questions-and-change-requests) in the user
guide describes the rounds, the listening and the replies.

## Story validation

The agent first calls `code_story_guide` for the writing rules, format and example bundled
with the installed plugin version. It calls `code_story_show` with the story and the
`projectPath` of the project. The IDE checks the story against the project files, and
saves and plays a valid story.

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

Without the MCP server, the skill writes the story file instead, and the plugin checks it
with the same location rules. Errors then appear in the tool window, because a file drop
has no report channel to the agent; see
[Without the MCP server](user-guide.md#without-the-mcp-server).

## Step kinds

A step without `kind` explains code, or introduces or closes the story. Two other kinds
help the reader, and the [timeline](user-guide.md#the-timeline) shows each of them in its
own way:

| `kind` | Use | Fields |
|---|---|---|
| `context` | Background that the step before it needs, for example a method that it calls or a setting that it reads. Context before the first explanation is background for the whole story. | Like any step: an optional `location`. |
| `proposal` | A change that the agent suggests but did not make, for example the fix of a bug trace. | An optional `location` for the lines that it changes, and an optional `proposedCode`: their new code, with `\n` between lines. An empty text proposes to remove the lines; without a location, it is new code. |

An unknown kind, or `proposedCode` on a step that is not a proposal, is an error. A
proposal of more than 30 lines gives a `proposal-too-long` warning. The reader can ask the
agent to apply a proposal: the request is a change request on the proposal step, and
`code_story_feedback` returns the step with its `kind` and `proposedCode`. After applying
it, the agent updates the step to an explanation, or removes it. Story files omit the kind
of an explanation, so stories without context or proposals are the same as before, and
older plugin versions show context and proposal steps as explanations.

## Patch operations

`code_story_patch` operations: `update` (stepId; title, comment, location, kind or proposedCode),
`insert` (afterStepId and a complete step), `remove` (stepId) and `confirm` (stepId).
Confirm makes the current code the step’s new reference and returns its status to **ok**;
the agent must read the current code first and check that the explanation still fits. A
missing file or range needs a new location or removal. Locations include anchor text; an
explicit null makes a step text-only, and a null `proposedCode` removes the proposed code.
A patch checks the whole structure and validates
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
  [`schema/story-v1.schema.json`](../schema/story-v1.schema.json) (`schemaVersion` 1).
  Paths are relative to the repository root; line numbers are 1-based and inclusive.
- A story has at most 15 steps, and a file at most 256 KB.
- The plugin records the creation time and Git state, and assigns stable IDs to steps
  that have none.
- `.codestory/local/pending-feedback.json` is the local index for the
  [feedback hooks](user-guide.md#optional-feedback-hooks).
- Reading positions, visited marks, unsent comments, the step card size and unread
  markers stay in IDE storage, never in story files.
