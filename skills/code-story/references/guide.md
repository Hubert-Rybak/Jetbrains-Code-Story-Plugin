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
3. Keep the IDs of steps you retain, and keep the saved `baseRef`. Call
   `code_story_show(story, storyId)` with the revised whole story. It replaces the same
   file, in the same local or shared folder. Its story ID and creation time, branch and
   commit stay the same. A playing story reloads at the same step number, or at the last
   step if it is now shorter.

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

## Show what changed

Set `baseRef` only for a story about a change: types `change` and `review`. Use the
commit before the change, for example the output of `git merge-base HEAD main` for a
branch. Use `HEAD` for uncommitted work. Do not set `baseRef` for `exploration` or
`bug-trace` stories, because their readers do not need diffs.

The plugin saves the full commit SHA, so `HEAD` keeps its meaning after the developer
commits. Each step then shows `A` when every line of its range is new since that commit,
or `M` when some lines changed. Steps without changes show no badge. The reader can open
"Show diff" to compare the base with the current file at the step. A `baseRef` that Git
does not know gives the warning `unknown-base-ref`; the story is saved but shows no
change information.

## Format and example

The `story` parameter is a typed JSON object, not a JSON string. Supply `title`,
`type`, `summary`, the original `request`, `author` (agent and model), and `steps`.
For a story about a change, also supply `baseRef`.
Each step has `title`, `comment`, an optional stable `id`, and either one `location`
object or no location. A location has `file`, `startLine`, `endLine` and `anchorText`.
The plugin assigns IDs to new steps whose IDs are omitted.
`children` and `links` are reserved and ignored in v1; do not use them.

The plugin adds `schemaVersion: 1`, the story `id`, `createdAt` in ISO 8601, `git`
(branch and commit, or null when unavailable) and `rangeText`, the stored code of each
range. Do not supply creation metadata or `rangeText`.
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
