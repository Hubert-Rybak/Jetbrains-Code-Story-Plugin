---
name: code-story
description: Builds a short, clickable code story and shows it in the developer's JetBrains IDE (Rider, IntelliJ IDEA and other IDEs with the Code Story plugin). Use when the developer types /code-story "<request>" ($code-story in Codex), or asks for a code story, a guided walkthrough or a tour of a change, a flow, a bug path or a review handoff.
---

# Code Story

A code story is a guided walkthrough of code in ordered steps. A step explains one small code range, or gives an overview or a list of checks without a location. The Code Story tool window shows the story header and step comments; a click on a code step opens the file and highlights the lines. Text-only steps leave the editor as it is.

## Workflow

1. **Find the subject.** Use the context of this conversation first: the change that you made, the ticket, the files that you read. If the conversation does not cover the request, explore the code until you understand the subject.
2. **Choose the type and steps** with the story rules below. Include a summary in 1 to 3 sentences, the original request text, and your agent name and model in `author`. Use `change`, `exploration`, `bug-trace` or `review` as the type.
3. **Verify every location.** Read each file before you use it in a step. Use the path from the Git repository root and the line numbers in the file as it is now. Line numbers start at 1, and a range includes its `endLine`.
4. **Call `code_story_show`** with the story. Always pass `projectPath`: the root folder of the project in the IDE. If you do not know it, use your working directory.
5. **Read the report.**
   - `"status": "accepted"`: tell the developer that the story is in the Code Story tool window. Give its title and number of steps.
   - `"status": "rejected"`: the IDE saved nothing. Each error has the step number (`null` for the whole story), a `code`, a `message` and a `hint`. Fix all errors, then call `code_story_show` again with the whole story. Read the file again before you change a location.
6. If the `code_story_show` tool is not available, or the IDE says that the project is not open, tell the developer: Code Story needs the IDE's MCP server (Settings | Tools | MCP Server) and the project open in the IDE.

## Story rules

1. **Context first.** Use the conversation. If it does not cover the subject, explore the code. Verify every location by reading the file.
2. **Order the steps by story type.**
   - Change: entry point, then core logic, data, side effects, and tests and configuration.
   - Exploration: follow the runtime flow, one hop per step.
   - Bug trace: trigger, then path, failure point, and the fix if you know it.
   - Review handoff: like a change, plus the risks and edge cases that the reviewer must check.
3. **Use 3 to 12 steps (15 at most).** If the subject needs more, make it narrower or split it into several stories, and tell the developer.
4. **One idea per step.** Point at the key lines only: usually 30 lines or fewer.
5. **Skip mechanical changes** (renames, formatting, generated code, lock files, imports). Mention them in one sentence at most.
6. **Use text-only steps when useful.** An overview or a final list of risks to check can omit `location`. Each code step has exactly one `location`, never a list of locations.
7. **Keep step IDs stable.** You may omit `id` on a new step; the plugin assigns one. When revising a step, keep its saved ID. IDs must be unique within a story. `children` and `links` are reserved and ignored in v1; do not use them.

## Writing rules

Write short, simple English, in the style of ASD-STE100 Simplified Technical English.

- A title has 8 words or fewer.
- A comment has 1 to 3 short sentences: what the code does and why it matters. A sentence has 20 words or fewer.
- Use the active voice and the present tense. Do not use semicolons or contractions.
- Put code names in backticks, for example `OrderService.retry`.

## Story format

The `story` parameter of `code_story_show` contains the agent fields below. The plugin adds `createdAt` and `git` (branch and commit at creation), assigns missing step IDs, and assigns the story ID from its file name with a number suffix for name collisions. Do not supply creation metadata. The saved format is in the bundled [JSON Schema](references/story-v1.schema.json).

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
      "location": { "file": "src/Payments/PaymentController.cs", "startLine": 42, "endLine": 57 }
    },
    {
      "title": "Charge the card",
      "comment": "The billing service sends the amount to the payment provider and saves the result.",
      "location": { "file": "src/Payments/BillingService.cs", "startLine": 20, "endLine": 35 }
    }
  ]
}
```
