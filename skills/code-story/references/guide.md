## Build the story

Use the conversation first: the request, ticket, change and files already discussed.
Explore the code when the conversation does not cover the subject. Read every file
before using its location. Explain what the code does and why it matters.

Choose a type and order the steps accordingly:

- `change`: entry point, core logic, data, side effects, then tests and configuration.
- `exploration`: runtime flow, one hop per step.
- `bug-trace`: trigger, path, failure point, then the fix if known.
- `review`: the change order, plus risks and edge cases for the reviewer to check.

Use 3 to 12 steps, with 15 as the hard limit. Narrow the subject or split it into
several stories if necessary, and tell the developer. Each step explains one idea.
Use the key lines only, usually 30 lines or fewer. Skip renames, formatting,
generated code, lock files and imports; mention mechanical changes in at most one
summary sentence. An overview or a final list of checks may be text-only.

## Stories about a change

Set `baseRef` only for a story about a change, usually of type `change` or `review`.
Do not set it for `exploration` and `bug-trace` stories. `baseRef` is the commit
before the change:

- For uncommitted work, use `HEAD`.
- For committed work on a branch, use the SHA of the commit where the branch starts,
  for example the output of `git merge-base HEAD main`.

The plugin saves the full SHA of the commit, so the base stays the same after the work
is committed. It compares the base with the current files, including uncommitted edits.
A step whose lines are all new since the base shows "A". A step with some new or changed
lines shows "M". "Show diff" opens the IDE diff viewer at the lines of the step. If Git
does not know the commit, the report has the warning `unknown-base-ref`. The story is
saved, but it shows no change information.

## Write clear English

These are our own practical rules, inspired by ASD-STE100 Simplified Technical
English. This guide does not reproduce its specification or dictionary.

- Use familiar, precise words. Keep the same name for the same thing.
- Use active voice and present tense. Name the actor when that makes the action clearer.
- Give instructions as direct commands. Put actions in the order the reader performs them.
- A story or step title has at most 8 words.
- A summary has 1 to 3 sentences.
- A comment has 1 to 3 sentences, or one lead sentence followed by 1 to 4 bullet items.
  Keep the entire comment, including its list, to at most 60 words.
- An instruction sentence has at most 20 words. A description sentence has at most 25 words.
  The mechanical check recognizes common imperative verbs and phrases such as "You must".
- Do not use semicolons or contractions. Write "does not" instead of "doesn't".
- Put code names in backticks, for example `OrderService.retry`. A code name counts
  as one word, including a code span with spaces. Link targets and Markdown markers do not count as words.

Use only inline code, bold, bullet lists and links to http/https URLs or repository
files (`src/App.kt#L2-L5`). Do not use headings, images, tables or HTML in story text.
Links open only when the reader clicks them.

Passive voice and -ing checks are optional hints, off by default. The developer can
enable them in Settings | Tools | Code Story. They are heuristics; keep necessary
technical nouns such as "string" and "mapping". There is no dictionary word check.

## Send or revise a story

Call `code_story_guide` before writing or revising a story. Its guide comes from the
installed plugin, and the response identifies that plugin's version.

Always pass `projectPath` to the project tools: the path of the project open in the IDE.
Paths in steps are relative to its Git repository root, or the project folder without Git.
Use `/` between folders. Line numbers start at 1 and include `endLine`. Absolute paths,
`..`, and symbolic links outside the repository are rejected.

With several Git repositories mapped in one project, every story stays inside one
repository. Pass `repositoryPath` (the absolute repository root) to `code_story_show`
when paths match several repositories or the story has no code locations. Do not
prefix step paths with the repository name. `code_story_list` returns `repositoryPath`
for each story; pass it to `code_story_get` and to replacements when an ID appears
in several repositories. Split a walkthrough across repositories into separate stories.

Each location has `anchorText`: copy the exact text of line `startLine` from the file.
Start each range at a line with code, for example a declaration, not at an empty line or
a lone brace. The plugin compares `anchorText` with the line that the IDE shows, including
unsaved edits. Indentation, trailing spaces and line endings do not matter. If the text is
at another line within 20 lines, the plugin moves the range to that line and reports a
correction, for example `Step 4: lines 120–131 changed to 123–134.` If the plugin cannot
find the text there, it rejects the story. The plugin stores the code of each range, so it
can find the code again when it moves and mark steps whose code changed.

For a new story, call `code_story_show(story)` without `storyId`. For a revision:

1. Call `code_story_list()` to find the ID. It lists local and shared stories with
   their title, type, creation date, branch, folder and status. `ok` means that every
   step points at its code. `moved` means that the plugin found code at other lines and
   updated them. `code-changed` means that the code in a range is different from the code
   that the step explains. `lost` means that the plugin cannot find the code of a step.
   `movedSteps`, `changedSteps` and `lostSteps` count the steps. Read the code again and
   revise `code-changed` and `lost` steps.
2. Call `code_story_get(storyId)` to read the full saved story.
3. Keep the IDs of steps you retain, and keep `baseRef`. Call
   `code_story_show(story, storyId)` with the revised whole story. It replaces the
   same file, in the same local or shared folder.
   Its story ID and creation time, branch and commit stay the same. A playing story
   reloads at the same step number, or at the last step if it is now shorter.

Read the report. It contains `status`, `storyId`, `file`, `errors`, `corrections`,
`warnings` and `next`. Each error, correction or warning has a 1-based step number (`null`
for the whole story), a code, a message and a fix hint. Structural, file, anchor and size
errors reject the whole story; nothing is saved. Corrections keep the story: the saved
story uses the corrected lines, so use them when you send the story again. Writing checks
and ranges longer than 30 lines are warnings only: the story is saved and plays. Warnings
appear only in the agent report, never in reader UI.

Fix all reported errors and writing warnings once, then send the whole story again.
For an accepted story, use the returned `storyId` on that second call so it replaces
the first version. For a rejected revision, keep the original `storyId`.
Read files again before changing locations. After this one fix round, do not loop on
style hints. Tell the developer the story's title, step count and that it is in the
Code Story tool window. Explain any remaining rejection if the story could not be saved.

## Answer reader feedback

Readers write comments on a step or on the whole story in the IDE, and submit them as
a numbered round. Each comment starts a thread. After showing a story and after
replying, listen by default with `code_story_listen(projectPath, repositoryPath)`.
Tell the developer you are listening, then keep the agent turn active. The tool
waits up to 25 seconds without blocking the IDE. On `timeout`, call again; on
`feedback`, process the returned `feedback` below, then listen again. Submit Round
requests processing that round without another chat prompt. On `stopped`, end the
loop. Also stop on cancellation or a new task from the developer.

Listening is enabled by default in Settings | Tools | Code Story. It works through
MCP without hooks, including with Copilot in Rider when its client supports active
waiting calls. It cannot restart an ended agent turn. If the client disconnects or
cannot keep listening, say so and use `/code-story feedback` (`$code-story feedback`
in Codex) to resume manually. Optional hooks only provide reminders for sessions
that are not listening. Never claim automatic listening without an active tool call.

Call `code_story_feedback()` for the comments of all stories, or
`code_story_feedback(storyId)` for one story. It returns the comments that wait for an
agent, oldest round first. Each comment has `storyId`, `round`, `threadId`, `kind`,
`target` (`story` or `step`), the `step` (ID, number, title, comment, current lines and
status), the comment `text` and the `earlierMessages` of its thread. `staleSteps` lists
the steps whose code changed or is lost. Fetched comments become "received": the reader
sees that an agent works on them. They stay available until an agent replies.
When there are no comments, the tool lists stale steps from all stories. Refresh these
with `code_story_patch`; no reply is needed without a comment. Refresh items update
the story only, without changing code.

Answer every comment with `code_story_reply(storyId, replies)`. Each reply has the
`threadId` and `round` of the comment, an `outcome` and a `text`:

- `answered`: the answer to a question. Answer from the story, the thread and the code.
  Never change code for a question. If the code needs a change, say so and suggest that
  the developer asks for a change request.
- `done`: a completed change or refresh request. Apply requested code changes with your
  normal permission prompts, then update, replace or confirm every affected step.
  Summarize the changes in `text` and supply `changedFiles`, an array of repository-relative
  code paths. Use `[]` for a story-only refresh. The plugin rejects `done` with
  `stale-steps` until all listed steps are reviewed after feedback was fetched. Later
  code edits require another review. Questions cannot use `done`.
- `declined`: you cannot or should not answer or make the change. Give the reason in 1 to 3 sentences.

Answers follow the writing rules above, with larger limits: 1 to 5 sentences, or one
lead sentence and up to 6 bullet items; 100 words at most. Answers may link to
repository files with lines, for example `src/App.kt#L2-L5`. Writing checks give
warnings only; use them in your next replies.

Each reply is saved or rejected on its own. The first reply to a comment wins: another
reply to it gets `already-answered`. A reply to a comment that you did not fetch gets
`not-fetched`. When the reader adds a comment to an answered thread, the thread opens
again in a new round; fetch it again before you reply. Fix rejected replies and send
only those again. The reader gets a notification, and the answers appear in the story.

Comment threads belong to the plugin. `code_story_get` returns them, but do not send them
to `code_story_show`. A revision keeps each thread on its step by step ID, so keep step
IDs stable. Threads of removed steps move to the story, with the title of the step.

## Format and example

The `story` parameter is a typed JSON object, not a JSON string. Supply `title`,
`type`, `summary`, the original `request`, `author` (agent and model), and `steps`.
Add `baseRef` only for a story about a change.
Each step has `title`, `comment`, an optional stable `id`, and either one `location`
object or no location. A location has `file`, `startLine`, `endLine` and `anchorText`.
The plugin assigns IDs to new steps whose IDs are omitted.
`children` and `links` are reserved and ignored in v1; do not use them.

The plugin adds `schemaVersion: 1`, the story `id`, `createdAt` in ISO 8601, `git`
(branch and commit, or null when unavailable) and `rangeText`, the stored code of each
range. It also writes the reader's comment `threads`. Do not supply creation metadata,
`rangeText` or `threads`.
Files are UTF-8 JSON, at most 256 KB. A new file is saved as
`.codestory/local/<yyyy-MM-dd>-<slug>.story.json`, with a number suffix for collisions.
The saved format is also described by `references/story-v1.schema.json` in the skill.

This example illustrates the format; verify paths and lines in the actual project:

```json
{
  "title": "How a payment is charged",
  "type": "exploration",
  "summary": "The controller checks the request. The billing service charges the card and saves the result.",
  "request": "Explain how a payment is charged",
  "author": { "agent": "Example Agent", "model": "<your model>" },
  "steps": [
    {
      "title": "The payment flow",
      "comment": "The request passes through validation, billing, and storage. Follow these steps to see each part."
    },
    {
      "title": "The controller takes the request",
      "comment": "`PaymentController.Charge` checks the amount and calls the billing service.",
      "location": {
        "file": "src/Payments/PaymentController.cs",
        "startLine": 42,
        "endLine": 57,
        "anchorText": "public IActionResult Charge(ChargeRequest request)"
      }
    },
    {
      "title": "Charge the card",
      "comment": "The billing service sends the amount to the payment provider and saves the result.",
      "location": {
        "file": "src/Payments/BillingService.cs",
        "startLine": 20,
        "endLine": 35,
        "anchorText": "public ChargeResult Charge(Card card, decimal amount)"
      }
    }
  ]
}
```


## Patch a story during feedback

Call `code_story_get` for the saved step IDs. To clarify an explanation without changing
code, call `code_story_patch(storyId, operations)` with `projectPath` and, when needed,
`repositoryPath`. Operations run in list order:

- Update: `{"op":"update","stepId":"entry","comment":"The controller checks the request."}`.
  You may supply `title`, `comment`, or a complete `location` with `anchorText`.
  Omitted fields stay unchanged; `location: null` makes the step text-only.
- Insert: `{"op":"insert","afterStepId":"entry","step":{"title":"Validation","comment":"Invalid requests stop here."}}`.
  The plugin assigns a stable ID when `step.id` is omitted. Supply an ID to refer to it
  in a later operation in the same call.
- Remove: `{"op":"remove","stepId":"old-step"}`. Its threads move to the story
  with the note “step removed”.

- Confirm: `{"op":"confirm","stepId":"entry"}`. Read the current code first and
  confirm only when the explanation still fits. The current range becomes the new
  reference and the status returns to `ok`. Re-anchor or remove a missing range.

A patch validates the complete structure and the code of edited steps. Untouched steps
retain their reference, including stale steps that still need review. It uses the same rules
for edited locations as `code_story_show` and
returns `status`, `storyId`, `file`, `errors`, `corrections`, `warnings`, and `next`.
Any error rejects all operations. The story keeps its file, metadata and threads.

In feedback mode, improve step text or insert steps if a question shows that the story
was unclear. Never change code for a question. Fetch feedback before editing, then
answer with `code_story_reply`. The plugin automatically adds “Story changes” to the
reply, based on net step edits while that comment was received. In a batch, each
received thread records the story edits made before its reply. Repeated updates count
once; reverted edits do not count. Updated and added step numbers refer to the revised
story; removed step numbers refer to the story before the edits. Automatic anchor
corrections do not create a change summary. Do not supply change summaries yourself.

Next: call `code_story_show` for a new story, `code_story_list` for a revision, or
`code_story_feedback` to answer reader comments manually. After showing or replying,
call `code_story_listen` to pick up the next submitted round automatically.
