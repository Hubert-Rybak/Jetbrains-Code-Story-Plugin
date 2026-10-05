# Code Story — Brainstorm

Date: 2026-10-05 · Result: [`spec.md`](spec.md)

## 1. The idea

A JetBrains IDE plugin for browsing "stories" about code.

- An AI coding agent (GitHub Copilot, OpenAI Codex, Claude Code) creates a story with a skill, for example `/code-story "Explain main functionality change for this ticket"`.
- The agent connects to a running IDE (Rider first) and adds the story to the plugin.
- A story lets the developer browse code in a structured way. Several modes can exist where they make sense. The first mode is step-by-step: the steps are listed in a tool window that the developer can dock anywhere, and each step can be clicked.
- Each step has a short comment, shown inline with the code where possible.
- The wording is short and easy to understand and follows ASD-STE100 (Simplified Technical English).

## 2. Method

- The design space was mapped as a decision tree: every decision opens the decisions that depend on it.
- The work went in rounds. Each round asked every open decision whose prerequisites were already settled (the "frontier"), each with a recommended answer.
- When a decision needed facts (IDE APIs, existing tools, agent capabilities), research ran first, and only the dependent questions waited for it.
- Five rounds and 32 questions, until no open decision remained. After the spec was published, a new idea (a reader feedback loop) opened a new branch, which took three more rounds (questions 33–47).

## 3. Final design tree

```
Code Story
├─ A Purpose
│  ├─ A0 Build own vs extend ............. build own; reuse Walkthrough (MIT) + Marginalis (Apache-2.0) ideas
│  ├─ A1 Use cases ....................... own change, review handoff, explore code, bug trace
│  ├─ A2 Authoring ....................... agent only; the plugin plays stories
│  └─ A3 v1 scope, roadmap, done ......... see sections 6 and 7
├─ B Platform
│  ├─ B1 IDE scope ....................... all IntelliJ-based IDEs, Rider is the primary target
│  ├─ B2 Minimum IDE version ............. 2025.3 (build 253), compiled with Java 21
│  ├─ B3 Rider .NET part ................. none in v1
│  ├─ B4 Build, CI, tests ................ official template; Plugin Verifier for Rider + IDEA; GitHub Releases
│  ├─ B5 Distribution .................... private first; Apache-2.0; "Code Story"; io.github.hubertrybak.codestory
│  └─ B6 Remote development .............. not supported in v1; single module
├─ C Agent integration
│  ├─ C1 Agents .......................... Claude Code (reference), Codex CLI, GitHub Copilot (Rider plugin + CLI)
│  ├─ C2 Channel ......................... MCP tools in the IDE's built-in MCP server + file-drop fallback
│  ├─ C3 Tool API & validation ........... guide / show / list / get; errors, corrections, warnings
│  ├─ C4 Project targeting ............... project path injected by the MCP server; never guess
│  ├─ C5 Skill delivery & MCP setup ...... plugin installs the skill; guides the MCP switch-on
│  ├─ C6 Context source .................. the conversation; free-text request
│  ├─ C7 Story-building rules ............ rules 1–5 (section 4, Q12)
│  └─ C8 Reader asks the agent ........... replaced by branch K (feedback loop)
├─ D Story model
│  ├─ D1 Later modes ..................... drill-down → diff walkthrough → flow map
│  ├─ D2 Story & step fields ............. title, type, summary, request, author, git, steps (one or no location)
│  ├─ D3 Code anchoring .................. line range + anchor text; follows edits
│  ├─ D4 Old vs new code ................. agent sets baseRef; plugin computes A/M from Git; Show diff
│  ├─ D5 File format ..................... own JSON + JSON Schema; CodeTour import/export in v1.1
│  └─ D6 Stale stories ................... ok / moved / code changed / lost; refresh through the feedback loop
├─ E Persistence
│  ├─ E1 Storage & sharing ............... .codestory/local (Git-ignored) + .codestory/shared (committed)
│  ├─ E2 Story management ................ play, stop, share, delete, copy as Markdown
│  └─ E3 Story root & path base .......... Git repository root; one .codestory per repository
├─ F UI / UX
│  ├─ F0 Dockable tool window ............ default right; user can move it
│  ├─ F1 Inline comment look ............. card above the range + highlight + gutter badges
│  ├─ F2 Tool window layout .............. toolbar; tree Local/Shared → stories → steps; details pane
│  └─ F3 Playback & navigation ........... auto-play, shortcuts, resume, finish, preview tab
├─ G Content
│  ├─ G0 Wording ......................... ASD-STE100, short
│  └─ G1/G2 Text format, limits, checks .. Markdown subset; length limits; warnings only
├─ H Security, privacy, performance ...... story-only tools; safe paths; no HTML; no network calls
├─ I Implementation defaults ............. IDs, overwrite on update, reader state, settings, themes
└─ K Reader feedback loop (v1)
   ├─ K0 Drafts + Submit per round ....... comments are drafts until "Submit round"
   ├─ K1 When it ships ................... v1; replaces "Ask agent…"
   ├─ K2 Where readers comment ........... on a step or on the whole story
   ├─ K3 Question vs change request ...... explicit toggle; a Question never changes code
   ├─ K4 Comment UI ...................... 💬 in the card, threads in the details pane, draft counts, unread dots
   ├─ K5 How a round reaches the agent ... pull on demand (/code-story feedback) + optional hook snippets; listening mode in v1.1
   ├─ K6 Where answers go ................ thread reply; may improve step text or insert steps
   ├─ K7 Change requests ................. applied directly by the agent
   ├─ K8 Steps in sync with code ......... "code changed" status; "done" rejected until steps are updated or confirmed
   ├─ K9 Thread states ................... submitted → received → answered / done / declined; reply reopens; resolve
   ├─ K10 Threads stored ................. in the story file (shared with it); drafts in IDE storage
   ├─ K11 Which session answers .......... any session; fetchable until answered; first reply wins
   ├─ K12 Answer text rules .............. STE, 100 words at most; automatic "Story changes" line
   ├─ K13 Agent API ...................... code_story_feedback / code_story_patch / code_story_reply
   ├─ K14 Who comments ................... the local reader and their own agent
   └─ K16 Skill feedback mode ............ /code-story feedback; "Request refresh" replaces "Copy refresh request"
```

## 4. Decisions by round

### Round 1 — Purpose and platform

| # | Decision | Answer | Why |
|---|---|---|---|
| Q1 | Use cases and audience | All four: understand your own change, review handoff, explore existing code, bug trace | The skill takes free text, and the step-by-step model fits all four. |
| Q2 | Who creates and edits stories | The agent only. The plugin plays stories (open, play, delete). Changes go through the agent. | Keeps v1 small; the agent is the author. |
| Q3 | IDE scope | All IntelliJ-based IDEs; Rider is the primary test target; no .NET part in v1 | Platform APIs (tool window, editor, inlays) work everywhere. File + line + text anchors need no C# symbol resolution. |
| Q4 | Agents in v1 | Claude Code (reference for testing), Codex CLI, GitHub Copilot. Junie should work but is not promised. | Agent-neutral mechanisms (open skill format, MCP) cover all three. |
| Q5 | How the skill finds its subject | From the conversation; the request is free text | "This ticket" was only an example; no ticket-specific logic. |
| Q6 | Later modes to plan for | Drill-down outline, diff walkthrough, flow map (branching comes free). Build order 1 → 2 → 3. No annotation layer. | The v1 format reserves child steps and links so these modes do not break it. |
| Q7 | Old vs new code in v1 | Current code + change information + "Show diff" | Diff steps come later with the diff walkthrough mode. |
| Q8 | Distribution | Private first (zip on GitHub Releases), Marketplace later | Ship fast; publish after real use and a name check. |

### Round 2 — Model, storage, skill rules

| # | Decision | Answer | Why |
|---|---|---|---|
| Q9 | Storage and sharing | New stories in `.codestory/local/` (ignored by its own `.gitignore`); "Share" moves a story to `.codestory/shared/` (committed). Sharing in v1. | No pull-request noise by default; sharing is one explicit action, and reviewers get the story with the branch. |
| Q10 | Story and step fields | Story: title, summary (1–3 sentences), original request, agent and model, creation time, Git branch and commit, steps. Step: ID, title (8 words at most), comment, optional location; `children`/`links` reserved. Text-only steps allowed; exactly one location or none per step. The header shows the request and the agent/model. | Two places in the code means two steps; readers always know the text is AI-written. |
| Q11 | Diff base and change information | The agent writes `baseRef` (commit SHA) only for stories about a change; the plugin computes added/modified from Git. Git only. | Automatic base detection would show unrelated diffs for exploration and bug-trace stories. |
| Q12 | Story-building rules | (1) Conversation first, otherwise explore; verify every location by reading the file. (2) Order by type: change, exploration, bug trace, review handoff. (3) 3–12 steps, 15 at most; otherwise narrow or split. (4) One idea per step; key lines, usually 30 or fewer. (5) Skip mechanical changes. | These rules decide story quality. |
| Q13 | Copilot variants | Both: the Copilot plugin in Rider and Copilot CLI | Both read the same skill folders. |
| Q14 | License, name, ID | Apache-2.0, "Code Story", `io.github.hubertrybak.codestory` | Same license as the IntelliJ Platform and the plugin template. |
| Q15 | Build our own or extend existing work | Build our own plugin; reuse ideas and code from Walkthrough and Marginalis with their license notices | See 5.5: Walkthrough conflicts with inline comments and lacks drift handling, sharing and wording support; Marginalis solves a different problem. |

### Round 3 — Channel, anchoring, format, UI

| # | Decision | Answer | Why |
|---|---|---|---|
| Q16 | Channel | MCP tools in the IDE's built-in MCP server as the main path; the agent writes a file into `.codestory/local/` as the fallback | Typed input and an immediate report for the agent; the folder watcher is needed anyway for shared stories. |
| Q17 | Code anchoring | Line range + anchor text; the plugin corrects small shifts, follows edits and marks lost steps. Symbol anchors only if this proves too weak. | Works without a .NET part and survives code movement. |
| Q18 | File format | Own JSON format with a JSON Schema and `schemaVersion`; CodeTour import/export in v1.1 | CodeTour's model has no child steps or links, so later modes would fight it. |
| Q19 | Inline comment look | A card above the range ("Step 3 of 8 · title", the comment, Prev / Next / Stop), a soft range highlight, the step number in the gutter, and small gutter badges for other steps in the same file. Visible only while a story plays. | The same mechanism as the IDE's pull-request review threads; a drawn callout is the fallback. |
| Q20 | Tool window and management | Toolbar (Play/Stop, Prev/Next, Share/Unshare, Delete, Copy as Markdown, Refresh); tree Local / Shared → stories → steps; details pane. No rename, edit, cleanup or import in v1. | Fits the agent-only model and the review handoff. |
| Q21 | Remote development | Not supported in v1; one module | Not used; a two-part plugin would add cost now. |

### Round 4 — Contracts and behavior

| # | Decision | Answer |
|---|---|---|
| Q22 | Versions, build, CI, tests | Minimum 2025.3 (build 253), compiled against 2025.3 with Java 21, no upper version limit. Kotlin, the official template and the IntelliJ Platform Gradle Plugin 2.x. Optional dependencies on the MCP Server and Git plugins. GitHub Actions: build, unit tests, Plugin Verifier for Rider and IDEA (2025.3, 2026.1, 2026.2, current early access); a tag publishes the zip to GitHub Releases. Tests: unit tests for the core, IDE-level tests for anchoring and tool handlers, a manual checklist for Rider (C#) and IDEA before each release. |
| Q23 | Tool API and validation | `code_story_guide()`, `code_story_show(story, storyId?)`, `code_story_list()`, `code_story_get(storyId)` with typed parameters. Report: errors (story rejected, nothing saved), corrections (accepted, lines shifted), warnings (accepted). Each item names the step and gives a fix hint. All or nothing. The file drop runs the same checks and shows problems in the tool window. |
| Q24 | Skill delivery and MCP setup | A short `SKILL.md` plus references (guide, JSON Schema, example). The plugin installs the skill into `.agents/skills/` and `.claude/skills/` (user level by default), versions it and offers updates. When the MCP server is off, a notification opens its settings; the plugin never changes the setting itself. "Check setup" action and a first-run notification. |
| Q25 | Reader asks the agent | v1: "Ask agent…" copies a prompt to the clipboard. The live question loop comes with the drill-down mode. *(Later replaced by the reader feedback loop, Q33.)* |
| Q26 | Playback and navigation | Auto-play for stories from MCP or the file drop; no auto-play for shared stories that arrive through Git. Preview tab, range in the upper third, text-only steps in the details pane. Own Next / Previous / Stop actions with shortcuts that can be changed. One story at a time. Finish on the last step. Resume and Restart. Editing while a story plays is allowed. |
| Q27 | Text format, limits, checks | Markdown subset (inline code, bold, bullet lists, safe links). Titles 8 words at most; summary 1–3 sentences; comment 1–3 sentences or a lead sentence and up to 4 list items, 60 words at most; sentences of 20 (instructions) / 25 (descriptions) words at most; English only. Mechanical checks in the plugin; warnings only, sent to the agent, not shown to readers. |
| Q28 | Stale stories | Step status ok / moved / lost; lost steps play with a warning; "2 of 9 steps lost" in the list; branch and commit in the header; "Copy refresh request"; moved anchors are saved for local stories only; no automatic deletion. *(Later extended by the "code changed" status, Q38, and "Request refresh", Q47.)* |
| Q29 | Security, privacy, performance | Tools only create and read stories; paths stay inside the project; no HTML; links only on click; size limits; "Written by <agent> (<model>)" label; no network calls or telemetry; the watcher covers only `.codestory/`; atomic writes. |

### Round 5 — Final

| # | Decision | Answer |
|---|---|---|
| Q30 | Story root and path base | `.codestory/` at the Git repository root; paths relative to it; the project folder without Git; one `.codestory/` per repository; one story stays inside one repository. |
| Q31 | Implementation defaults | Story ID = file name; updates overwrite (no version history in v1); anchor checks use the text the IDE shows, ignoring whitespace and line-ending differences; project not open → error, then file-drop fallback; with two IDEs on one repository, an MCP story plays only in the IDE that received it; reader state stays in IDE storage; settings page under Tools \| Code Story; theme-aware colors; "Copy as Markdown" uses repository-relative links with line ranges. |
| Q32 | v1 scope, roadmap, done criteria | Accepted as in sections 6 and 7 and in the spec. |

### Round 6 — Reader feedback loop: scope and behavior

The idea: readers comment under steps, collect several comments, and "submit" them back to the agent. Questions get answers written back into the story; change requests give the agent the context to change code; any code change must also update the matching steps.

| # | Decision | Answer | Why |
|---|---|---|---|
| Q33 | When it ships | v1; it replaces the "Ask agent…" clipboard prompt. The live waiting part may move to v1.1 if it proves costly. | It makes stories interactive, and v1 drops "Ask agent…" in exchange. |
| Q34 | Where readers comment | On a step, and on the whole story | A step already points at its code; a separate line-range anchor adds little. |
| Q35 | Question or change request | The reader picks the kind with a toggle (Question by default). For a Question, the agent never changes code; it suggests a change request instead. | No code changes that nobody asked for. |
| Q36 | Where answers go | A thread reply under the comment; the agent may also improve the step text or insert steps, and the reply lists those story changes | Answers stay next to the code they explain. |
| Q37 | Change requests | The agent applies them directly (its permission prompts still apply) and replies "done" with a summary | The reader asked explicitly; a plan-first step would cost a round each time. |
| Q38 | Steps in sync with code | New step status "code changed" (the code in the range differs from the code the step was written for). The plugin rejects "done" until every affected step is updated or confirmed. It also covers the developer's own edits. | Meets the requirement that any code change also updates the relevant steps. |
| Q39 | Who comments and answers | The local reader and their own agent | Sending comments to another person needs a server or the PR platform. |

### Round 7 — Reader feedback loop: UI, states, storage, text

| # | Decision | Answer |
|---|---|---|
| Q40 | Comment UI | 💬 button in the card with a small input (text, kind toggle, Save draft); collapsed thread line in the card; full threads and "Comment on story" in the details pane; editable drafts with counts; one "Submit round" per story; open-thread counts and unread dots in the tree; a notification when answers arrive; a shortcut for "Comment on current step"; no writing checks on reader comments. |
| Q41 | Thread states | draft → submitted (can be withdrawn until fetched) → received → answered / done / declined (with a reason); numbered rounds per story; the agent replies to every comment, and comments without a reply stay pending; a reply reopens a thread; resolved threads are hidden until "Show resolved". |
| Q42 | Storage and sharing | Threads live in the story file and travel with a shared story. Drafts and unread markers stay in IDE storage until Submit. |
| Q43 | Answer text | ASD-STE100, Markdown subset, 1–5 sentences or a lead sentence and up to 6 list items, 100 words at most; links to project files; "done" replies summarize the code changes, and the plugin adds a "Story changes" line; "declined" replies give a reason. |

### Round 8 — Reader feedback loop: delivery and API

| # | Decision | Answer |
|---|---|---|
| Q44 | How a round reaches the agent | v1: pull on demand. After Submit, the IDE shows "Waiting for your agent: tell it `/code-story feedback`" with a copy button (`$code-story feedback` in Codex). Optional hook snippets (Claude Code and Codex at session start and on each prompt, Copilot CLI at session start) tell the agent that feedback is pending; the plugin keeps a small pending-feedback index for them and never installs hooks itself. Listening mode moves to v1.1. |
| Q45 | Which session answers | Any session in the project. Fetching marks comments "received"; they stay available until answered; the first reply wins, and a second gets "already answered". |
| Q46 | Agent API | `code_story_feedback(storyId?)` (pending comments with full context, plus steps in "code changed" or "lost" status); `code_story_patch(storyId, operations)` (update, insert, remove, confirm steps); `code_story_reply(storyId, replies)` (answered / done / declined, with the "done" check). Threads belong to the plugin and are kept by step ID when a story is replaced. Every result names the next call. |
| Q47 | Skill feedback mode | `/code-story feedback [storyId]`: fetch oldest first; answer questions without code changes; apply change requests, update the affected steps and reply "done"; refresh "code changed" and "lost" steps even without comments; summarize in the chat. "Request refresh" (adds a refresh item to the next round) replaces "Copy refresh request". |

## 5. Research findings (status 2026-10-05)

### 5.1 How an agent reaches a running IDE

- **Built-in MCP server.** Bundled since 2025.2 in IntelliJ IDEA and Rider. The plugin is enabled by default, but the server stays off until the user switches it on in Settings | Tools | MCP Server and accepts a consent dialog.
  - Transports: SSE (since 2025.2), stdio through a JVM proxy (since 2025.2), and streamable HTTP (since 2026.1).
  - Port 64342 up to 2026.1; since 2026.2 each product has its own default (Rider: 64482). If the port is busy, the next free port is used.
  - Security: loopback only, with Host/Origin checks, but no auth token. Terminal and run tools ask for confirmation; the 2026.3 early-access build adds a consent check on the first call.
  - "Auto-Configure" writes the MCP configuration of Claude Code (since 2025.2), Codex (2026.1), Junie and Copilot CLI (2026.2), and of Copilot's JetBrains plugin.
  - Project choice: since 2025.3 every tool gets an injected `projectPath`; without it, the server tries a header or environment variable, the client's MCP roots, then the only open project, and otherwise returns an error that lists the open projects. Several IDE instances have separate ports; the client configuration decides which one answers.
- **Third-party MCP tools are supported.** The extension point `com.intellij.mcpServer.mcpToolset` (dynamic), the `McpToolset` interface and the `@McpTool` / `@McpDescription` annotations. The plugin declares a dependency on `com.intellij.mcpServer` (it can be optional). Minimum build 252; 253 for `projectPath`. The API is not marked internal, but it is not in the platform's frozen API list and still grows, so compile against the oldest supported version. Rider supports it: a third-party ReSharper toolset plugin uses it on Rider 2026.2.3.1. The IDE's own chat agents probably see third-party tools too.
- **Alternatives.**
  - A REST service on the built-in web server (port 63342): public API and always on, but the agent must find the port, Codex's sandbox blocks `curl` by default, and the plugin must add its own protection against browser requests.
  - An own MCP server with the Kotlin MCP SDK: possible, but it repeats ports, origin checks and client configuration, and Kotlin/Ktor versions must match the IDE.
  - Claude Code's JetBrains plugin declares no extension points, so other plugins cannot hook into it.
  - File handoff: the IDE notices outside changes after an asynchronous refresh (native file watcher or window activation). It works with every agent and sandbox, but gives no reply to the agent. Write a temp file, then rename it.
- **Versions.** IntelliJ IDEA 2026.2.3 and Rider 2026.2.3.1 are current; 2026.3 is in early access.

### 5.2 Prior art

- **CodeTour (VS Code).** The reference format for code tours: `.tour` JSON files in `.tours/`, `.vscode/tours/` or `.github/tours/`. Tour fields: `title`, `description`, `ref`, `isPrimary`, `nextTour`, `stepMarker`, `when`, `steps`. Step fields: `description` (Markdown), `title`, a target (`file`, `directory`, `uri` or `view`), `line` (1-based), `selection`, `pattern` (regex, used when there is no line) and `commands`. Playback uses comment threads at the line with Previous / Next. Drift handling: `pattern`, marker comments and `ref`. Maintenance only (v0.0.61, August 2025).
- **JetBrains ports of CodeTour** are partial and inactive: "CodeTour" (2023, reads a subset of fields, file-name-only resolution), "MyCodeTour" (2025 fork with diagrams), and prototypes.
- **Walkthrough** (forketyfork, MIT, first release May 2026, about 1.2k downloads): an agent pushes steps through four MCP tools (show steps, show diff steps, wait for a reader question, insert child steps). Steps appear in a floating popup with a pointer to the code. History in `.idea/walkthroughs/`, Markdown export, a companion skill. Needs 2026.1+; not tested on Rider.
- **Marginalis** (MuhammadFarag, Apache-2.0, on the Marketplace since August 2026): comment threads inline in the editor, created by an agent through a REST service on the built-in web server; ordered walkthroughs; re-anchoring by text; a 409 "stale anchor" answer to the agent.
- **Other tools.** Swimm (code-coupled documents), Sourcegraph Deep Search (web), Windsurf Codemaps (agent-built maps, Windsurf only), DeepWiki (generated wikis), JetBrains AI Assistant (explain commit, self-review) and Junie's review command, CodeRabbit walkthroughs (web), the awesome-copilot `code-tour` skill (writes CodeTour files, with a validator), several VS Code-only tour extensions from 2026, and many JetBrains plugins that show agent review comments inline.
- **Gap.** No tool combines live push from an agent, inline comments in the editor, tested Rider support, controlled wording (ASD-STE100), stories built around a change, sharing through Git, and CodeTour-compatible import/export.

### 5.3 Editor UI, Rider and build tooling

- **Inline UI options.**
  - Block inlays and after-line-end inlays: stable public API. Clicks reach the renderer, so drawn buttons can work.
  - Component inlays: real Swing components (real buttons) between code lines. Marked experimental (from 2023.3 to the current version), but JetBrains' own GitHub/GitLab pull-request review threads use them, and they ship in Rider. They are skipped on remote-development hosts.
  - Range highlighters with gutter icons and editor notification banners: stable.
  - Declarative inlay hints and Code Vision: a poor fit for steps placed on demand.
- **Rider architecture.** The frontend (IntelliJ, JVM) has only a light syntactic model for C#; the semantic model, symbol resolution and find usages live in the ReSharper backend (.NET), reached through the rd protocol. A frontend-only plugin can open files, navigate, add highlighters, inlays and tool windows. C# symbol lookup would need a backend part (or undocumented frontend contributors).
- **Build tooling.** IntelliJ Platform Gradle Plugin 2.19.0 and Plugin Verifier 1.410 (September 2026). Target Rider with `useInstaller = false` plus a JetBrains Runtime dependency. Java 21 for 2024.2–2026.1 and Java 25 for 2026.2. Do not bundle the Kotlin standard library. The official plugin template is maintained. Run the Plugin Verifier against every target IDE type explicitly.
- **Remote development.** Component inlays do not show on a remote-development host; support needs a plugin split into frontend and backend modules.

### 5.4 Agent skills and ASD-STE100

- **Agent Skills format** (agentskills.io): `SKILL.md` with YAML front matter (`name`, `description`, optional fields) and optional `scripts/`, `references/` and `assets/` folders.
  - Claude Code reads `.claude/skills/` (project) and `~/.claude/skills/` (user), but not `.agents/skills/`. Invocation: `/code-story <text>`.
  - Codex reads `.agents/skills/` and `~/.agents/skills/`. Invocation: `$code-story`.
  - GitHub Copilot (CLI, coding agent, VS Code, JetBrains plugin; skills in the JetBrains plugin since June 2026) reads `.github/skills/`, `.claude/skills/` and `.agents/skills/`. Invocation: `/code-story`.
  - Junie reads `.junie/skills/` and `.agents/skills/`.
  - Result: one skill folder copied to `.agents/skills/` and `.claude/skills/` reaches all target agents.
  - Claude Code plugin marketplaces prefix plugin skills (`/code-story:code-story`), so the skill is installed as a plain skill instead.
- **Precedent.** The IDE's MCP server already writes the configuration of other agents ("Auto-Configure"), and JetBrains AI Assistant installs skills into these folders.
- **ASD-STE100.** Issue 9 (15 January 2025, 53 rules; "technical names" became "technical nouns"); Issue 10 is planned for 2028. The specification is free on request, but ASD owns the copyright, so the dictionary must not be shipped and the rules must be paraphrased. A 2026 white paper from the STE maintenance group asks for human review of AI-written text and a statement when AI helped to write it. Key rules for short comments:
  - Words: approved words only, plus technical nouns and technical verbs; no more than three nouns in a row; American English spelling.
  - Verbs: limited forms (infinitive, imperative, simple present, simple past, simple future, past participle as an adjective); -ing forms only in technical nouns; active voice.
  - Sentences: no contractions; vertical lists; articles; instructions of 20 words or fewer, one instruction per sentence, written as commands, conditions first.
  - Descriptions: sentences of 25 words or fewer; a topic sentence; one topic and six sentences or fewer per paragraph.
  - Punctuation and counting: no semicolons; no phrasal verbs; an identifier counts as one word.
- **Checkers.** Commercial tools exist (HyperSTE, Congree, Acrolinx, TechScribe); there is no Java or Kotlin open-source STE checker. Mechanical checks (length, semicolons, contractions, -ing forms, passive voice, noun clusters) are easy to write; word checks need a user-supplied word list.

### 5.5 Build or extend: Walkthrough and Marginalis

- **Walkthrough** (about 5.3k lines of Kotlin, one maintainer, no outside pull request ever merged):
  - The floating popup is about 55% of the code, and the project requires all UI to stay in Compose/Jewel. Inline comments would go against its design.
  - Bad files or lines are not reported to the agent; the step silently shows without an anchor.
  - No delete, no keyboard navigation, no range highlight, no drift handling, no sharing model, no CodeTour support. Needs 2026.1+.
  - Worth reusing: the MCP toolset pattern and tool descriptions that guide the agent's next call, overwriting a saved story by ID, the diff-viewer integration, the parent/child step labels, a wait call that returns before the agent's tool timeout, atomic writes and the path-traversal guard, the Markdown exporter.
- **Marginalis** (about 9.6k lines, one author, pull requests disabled):
  - Two-way review conversations, which is a different problem; single-line anchors; no diff support.
  - Its REST service trusts every caller, so any web page could post to it; do not copy that.
  - Worth reusing: the inline panel and its cleanup on plugin unload, the anchor policy (search ±20 lines for the stored text) and its tests, the 409 "stale anchor" / "line adjusted" answers, never guessing the project, a guide served by the plugin so the skill always matches the installed version, batch calls with results per item, navigation bound to shortcuts.
- **Verdict:** build our own plugin and reuse these ideas, with typed MCP parameters instead of JSON inside strings, and with the `.codestory/` storage and the CodeTour mapping designed from day one. Walkthrough can serve as a stopgap until v1 exists.

### 5.6 How a feedback round can reach the agent

- **No server push.** None of the checked agents wakes up on a message that the MCP server starts. Claude Code can do it only through "channels", a research preview behind a developer flag; Codex only logs such messages; Copilot and Junie document nothing. The IDE's MCP server offers toolsets only progress forwarding (since 2026.2) and elicitation inside a running tool call.
- **Waiting tool calls are fragile.**
  - Claude Code: for HTTP servers, the request timer runs until the first response byte and is at least 60 s; the IDE's server sends nothing before the result, so calls are probably cut after about 60 s unless the user raises the server's timeout. Calls that run longer than 2 minutes move to the background and wake Claude when they finish.
  - Codex: 300 s since June 2026 (120 s and 60 s in older versions); the call blocks the turn.
  - Copilot CLI: 30 s by default. Copilot in Rider and Junie: unknown.
- **Hooks can remind the agent.** Claude Code (session start, each prompt, stop, background re-wake), Codex (session start, each prompt, stop) and Copilot CLI (session start, agent stop, notifications) can add context from a script, for example one that reads a pending-feedback index. Copilot's Rider plugin has no suitable hook. Junie hooks are in early access.
- **Prior art.** Marginalis holds a long request (up to hours) that the agent runs in the background, uses a cursor so a round is never delivered twice, and enables "Submit round" only while an agent listens. Walkthrough repeats a 110 s wait call. Both keep an item available until it is answered.
- **Conclusion:** keep rounds durable in the story files, let any agent pull them with a non-blocking tool (`/code-story feedback`), offer hook snippets as an opt-in reminder, and add a bounded listening mode later.

## 6. Roadmap

- **v1:** everything in the spec: step-by-step stories through MCP with the file-drop fallback, inline card, tool window, anchoring and drift handling, change information, sharing, text checks, skill installation and setup, and the reader feedback loop (comments, rounds, answers, change requests, steps kept in sync with the code).
- **v1.1:** CodeTour import and export; listening mode for feedback rounds.
- **v2:** drill-down mode (child steps) with the live question loop (the reader asks in the card, the agent answers with child steps).
- **v3:** diff walkthrough mode (steps on the old side of a diff).
- **v4:** flow map mode (a diagram of components or calls; clicking a box opens the code).
- **If needed:** C# symbol anchors through a ReSharper part; remote development through a two-part plugin; a word-list check against the user's own copy of the ASD-STE100 dictionary.
- **Marketplace:** after a name check ("CodeStory" is also the name of the company behind the Aide editor) and a few weeks of daily use without blocking bugs.

## 7. v1 done criteria

1. In Rider 2026.2 on a real C# solution, `/code-story "<request>"` in Claude Code creates a story through MCP; it plays, and every step points at the right code.
2. The same flow works with Codex CLI (`$code-story`), GitHub Copilot in Rider and Copilot CLI.
3. With the MCP server off, the file-drop fallback works.
4. In the usual case, the agent fixes a story from the report's errors and warnings without help.
5. A story that is shared, committed and checked out in a second clone plays there.
6. Editing code during playback keeps the anchors right; after large edits, lost steps show a warning.
7. The Plugin Verifier passes for Rider and IntelliJ IDEA from 2025.3 to the current early-access build, and the unit and IDE tests pass in CI.
8. The manual checklist passes in Rider (C#) and IntelliJ IDEA (Java/Kotlin), in light and dark themes.
9. A reader comments on steps, submits a round, and `/code-story feedback` answers the questions in the story and applies a change request; the affected steps are updated or confirmed, and "done" is rejected until they are.

## 8. Risks and watch points

- **The MCP toolset API is not frozen.** Compile against 2025.3, verify against newer versions and the early-access build in CI.
- **Component inlays are experimental.** Keep the drawn-callout fallback working; test both in C# editors early.
- **The MCP server is off by default.** Onboarding must make the switch-on step obvious; the file drop covers the gap.
- **The MCP server has no token.** Any local program can call our tools, so the tools only create and read stories and validate all input.
- **Agents give wrong line numbers.** The anchor text check corrects small shifts and rejects wrong locations with a fix hint.
- **The space moves fast.** Most related tools appeared between May and October 2026. Differentiate on Rider quality, inline cards, drift handling, sharing and controlled wording.
- **ASD-STE100 copyright.** Paraphrase the rules; never ship the dictionary.
- **Name collision.** Check the name "Code Story" before a public release.
- **Feedback needs a hand-over step.** Without server push, the developer tells the agent `/code-story feedback`; hooks only remind. Keep the copy button and the "waiting for your agent" message obvious.
- **Agent tool-call limits change often.** Re-check them before building listening mode in v1.1.

## 9. Sources

Agent connection and IDE platform:

- https://www.jetbrains.com/help/idea/mcp-server.html
- https://www.jetbrains.com/help/rider/mcp-server.html
- https://github.com/JetBrains/intellij-community/blob/master/plugins/mcp-server/README.md
- https://github.com/JetBrains/intellij-community/tree/idea/2026.2.3/plugins/mcp-server
- https://platform.jetbrains.com/t/mcp-integration-in-jetbrains-plugins-recommended-approach-future-support/4171
- https://www.jetbrains.com/help/mps/writing-mps-mcp-tools-plugin.html
- https://github.com/JetBrains/mcp-jetbrains
- https://code.claude.com/docs/en/jetbrains
- https://code.claude.com/docs/en/mcp
- https://developers.openai.com/codex/agent-approvals-security
- https://docs.github.com/en/copilot/how-tos/provide-context/use-mcp/extend-copilot-chat-with-mcp?tool=jetbrains
- https://github.com/Bathur/ReSharperMcpToolset
- https://github.com/modelcontextprotocol/kotlin-sdk
- https://plugins.jetbrains.com/docs/intellij/virtual-file-system.html
- https://blog.jetbrains.com/dotnet/2026/09/07/rider-2026-3-eap/

Editor UI, Rider and build tooling:

- https://github.com/JetBrains/intellij-community/blob/master/platform/editor-ui-api/src/com/intellij/openapi/editor/InlayModel.java
- https://github.com/JetBrains/intellij-community/blob/master/platform/platform-impl/src/com/intellij/openapi/editor/ComponentInlay.kt
- https://github.com/JetBrains/intellij-community/blob/master/platform/collaboration-tools/shared/src/com/intellij/collaboration/ui/codereview/editor/editorComponentInlaysUtil.kt
- https://plugins.jetbrains.com/docs/intellij/inlay-hints.html
- https://plugins.jetbrains.com/docs/intellij/informing-users.html
- https://plugins.jetbrains.com/docs/intellij/intellij-platform.html#rider
- https://www.jetbrains.com/help/resharper/sdk/Rider.html
- https://github.com/JetBrains/resharper-rider-plugin
- https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html
- https://github.com/JetBrains/intellij-platform-gradle-plugin/blob/main/CHANGELOG.md
- https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
- https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html
- https://plugins.jetbrains.com/docs/intellij/using-kotlin.html
- https://github.com/JetBrains/intellij-platform-plugin-template
- https://plugins.jetbrains.com/docs/intellij/split-mode-and-remote-development.html

Prior art:

- https://github.com/microsoft/codetour
- https://plugins.jetbrains.com/plugin/19227-codetour
- https://plugins.jetbrains.com/plugin/27323-mycodetour
- https://plugins.jetbrains.com/plugin/31637-walkthrough
- https://github.com/forketyfork/walkthrough-plugin
- https://plugins.jetbrains.com/plugin/33135-marginalis
- https://github.com/MuhammadFarag/marginalis
- https://plugins.jetbrains.com/plugin/20716
- https://cognition.com/blog/codemaps
- https://cognition.com/blog/deepwiki-mcp-server
- https://sourcegraph.com/blog/deep-search
- https://www.jetbrains.com/help/ai-assistant/ai-in-vcs-integration.html
- https://blog.jetbrains.com/junie/2026/06/junie-coding-agent-out-of-beta/
- https://docs.coderabbit.ai/pr-reviews/walkthroughs
- https://github.com/github/awesome-copilot/tree/main/skills/code-tour

Agent skills and ASD-STE100:

- https://agentskills.io/specification
- https://code.claude.com/docs/en/skills
- https://developers.openai.com/codex/skills
- https://docs.github.com/en/copilot/concepts/agents/about-agent-skills
- https://junie.jetbrains.com/docs/agent-skills.html
- https://www.jetbrains.com/help/ai-assistant/agent-skills.html
- https://www.asd-ste100.org/STE_faq.html
- https://www.asd-ste100.org/STE_downloads.html
- https://simplified-english.co.uk/rules-ste9.html

Feedback delivery:

- https://code.claude.com/docs/en/env-vars
- https://code.claude.com/docs/en/hooks
- https://learn.chatgpt.com/docs/extend/mcp.md
- https://learn.chatgpt.com/docs/hooks.md
- https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-command-reference
- https://docs.github.com/en/copilot/reference/hooks-reference
- https://github.blog/changelog/2026-03-11-major-agentic-capabilities-improvements-in-github-copilot-for-jetbrains-ides/
- https://devblogs.microsoft.com/java/unlocking-mcp-in-jetbrains-how-copilot-uses-sampling-prompts-resources-and-elicitation/
- https://modelcontextprotocol.io/specification/2026-07-28/changelog
- https://github.com/forketyfork/agentic-skills/blob/main/skills/walkthrough/skills/walkthrough/SKILL.md
