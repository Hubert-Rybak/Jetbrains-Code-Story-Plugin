# Manual release checklist

Before tagging a version, install `build/distributions/*.zip` from disk in
Rider (a C# solution) and IntelliJ IDEA (a Java or Kotlin project). Repeat
the UI checks in light and dark themes. Record the IDE versions used.

- [ ] The plugin installs and enables without errors.
- [ ] **View | Tool Windows | Code Story** opens the window. Without stories,
      it says "No stories yet".
- [ ] On a fresh IDE profile, the window is docked on the right.
- [ ] The IDE's normal controls can dock it left or bottom, or float it.
- [ ] Closing and reopening the window works.
- [ ] The window is available while the IDE indexes the project.
- [ ] With MCP Server and Git plugins disabled in a test profile, Code Story
      still loads and the window opens.
- [ ] The IDE log has no Code Story loading or runtime errors.
- [ ] CI build, checks and all eight Plugin Verifier jobs pass.
- [ ] The release tag matches `version` in `gradle.properties` and
      `skills/code-story/version.txt`.
- [ ] CHANGELOG.md has an entry for the version, with the release date.
- [ ] For 1.0.0, every v1 done criterion in [v1-verification.md](v1-verification.md)
      has a result.

## Agents

Install the skill with **Tools | Code Story | Install Code Story skill**.
Switch on the MCP server (Settings | Tools | MCP Server), and connect each agent as the
[README](../README.md#set-up-your-agent) describes. Follow the README step by step, so
that these checks also test its setup instructions.

- [ ] Claude Code: Auto-Configure, then `/code-story "<request>"` creates a story
      through `code_story_show`, and `/code-story feedback` answers a round.
- [ ] Codex CLI: `$code-story "<request>"` and `$code-story feedback` work the same way.
- [ ] GitHub Copilot in Rider (Agent mode): `/code-story "<request>"` and
      `/code-story feedback` work the same way.
- [ ] GitHub Copilot CLI: `/code-story "<request>"` and `/code-story feedback` work the
      same way.
- [ ] **Check setup** lists the seven Code Story tools and the installed skill copies.
- [ ] The optional hook snippets from **Copy feedback hooks** remind Claude Code, Codex
      and Copilot CLI of pending feedback, and print `{}` when nothing is pending.

## Setup and file drops

- [ ] A fresh profile shows one first-run notification with Install and MCP settings
      actions. Opening another project does not repeat the first-run steps.
- [ ] Install writes the skill, guide, schema, example and version to both user
      agent folders. The project-level option writes both copies at the story root.
- [ ] After upgrading the plugin, old copies get an update offer. The action updates
      installed copies at both levels, and Check setup shows their current versions.
- [ ] Check setup shows the server on/off state, registered tools, and all installed
      skill locations and versions. The settings action opens MCP Server settings.
      Code Story never changes the MCP enable setting.
- [ ] With MCP Server disabled, the agent writes a file through the fallback skill.
      The story appears and plays; an invalid drop shows errors in the tool window.
      Correcting or deleting the file clears its errors.
- [ ] A file dropped while the project is closed plays after opening the project.
- [ ] With the same repository open in two IDEs, a drop plays in both. An MCP story
      plays only in its receiving IDE. Anchor rewrites do not restart stopped stories.
- [ ] Temporary files and files outside `.codestory/` do not trigger story handling.

## Agent delivery

- [ ] `/code-story "<request>"` in the agent creates a story through
      `code_story_show`. The tool window shows it and selects it.
- [ ] The story file is in `.codestory/local/` at the Git repository root,
      and `git status` does not show it.
- [ ] A click on each step opens the right file, puts the range in the upper
      part of the editor and highlights the range. The highlight is readable
      in light and dark themes.
- [ ] Stories appear newest first with title, date, branch and distinct type
      icons. Code steps show their number, title, file and line range.
- [ ] Selecting a story shows its summary, original request, "Written by
      <agent> (<model>)", creation date, branch and commit in the details pane.
- [ ] Switching Git branches updates the note in the selected story's details.
      Detached HEAD and repositories without commits display clearly.
- [ ] Clicking or pressing Enter on a text-only step shows its comment in
      the details pane and leaves editor tabs, caret, scroll and highlights as they are.
- [ ] Without the Git plugin, stories still save and play; creation time and
      author remain visible, and Git metadata is shown as unavailable.
- [ ] A story with a wrong path or wrong lines is rejected: the agent gets the
      errors, fixes the story, and sends it again. Nothing is saved for the
      rejected story.
- [ ] With two projects open, the story goes to the project that the agent
      names in `projectPath`.

## Story playback

- [ ] The tree groups Local and Shared stories, under repository names only when
      several Git repositories are mapped in the project.
- [ ] Share and Unshare move the same story file between the two folders, preserving
      its ID and playback position. Shared files can be committed with the branch.
- [ ] Delete asks for confirmation. Cancel keeps the story; Delete removes its file
      and stops it if it is playing.
- [ ] A shared story checked out in a second clone appears as new without starting
      playback. Playing it clears the marker across IDE restarts, without editing the file.
- [ ] Copy as Markdown works from the toolbar and context menu, including when a
      step is selected. The pasted text has title, summary, comments and relative line links.
- [ ] With two repositories, each story saves in its own repository. A story with
      steps in both is rejected. Identical story IDs keep separate reading positions.

Repeat these checks in Rider (C#) and IntelliJ IDEA (Java/Kotlin), in light
and dark themes, including narrow and split editors.

- [ ] A step shows a floating card with a pointer to its code range, the step
      number and title, the Markdown comment and the navigation buttons.
      Showing and closing the card adds no space between code lines.
- [ ] The card leaves the target code and caret visible. When it cannot fit
      beside the code, the same comment and controls appear in the tool window.
- [ ] Showing or repositioning the card leaves keyboard focus in the editor.
      Typing, code selection and clicks in the editor keep playback and the
      card available. Card controls also work from the keyboard.
- [ ] Scrolling and resizing keep the pointer at the current range without
      scrolling back automatically. The floating card hides when its target
      is offscreen or its tab is inactive, and returns when the target is
      visible again unless it was explicitly dismissed.
- [ ] Closing the card keeps the current step, highlight and badges.
      Clicking the current gutter badge or selecting the current step reopens
      it. Playing another step shows that step's card.
- [ ] Prev and Next work across files from the card, tool window and Keymap
      actions. Next becomes Finish on the last step. Stop and Finish remove
      the card in either location, the highlight and all story badges.
- [ ] Other steps in the same file have numbered gutter badges; clicking a
      badge plays that step. Playing another story clears the previous one.
- [ ] An accepted MCP story opens the tool window and plays step 1 in the
      receiving IDE. The bring-to-front setting works both on and off; the
      card does not take keyboard focus from the editor.
- [ ] The agent gets the installed version's guide, lists local and shared
      stories, and fetches a full story. Replacing it keeps its ID, folder and
      creation metadata. During playback it keeps the current step, or the
      last step when the revision is shorter, and displays the revised text.
- [ ] Writing and long-range warnings appear in the agent report and do not
      block the story. No writing warnings appear in the card, tree or details.
      Passive voice and -ing hints are off by default and can be enabled in
      **Settings | Tools | Code Story**.
- [ ] Play resumes the last step after Stop; Restart starts at step 1. Visited
      marks last for the current IDE session, and reading position is not
      written to the story file.
- [ ] Closing the target editor or project removes its floating card without
      leaving an orphan card or errors in the IDE log.
- [ ] **Settings | Tools | Code Story** has bring-to-front enabled and the
      theme highlight color by default. A custom color takes effect during
      playback; switching back to the theme default and changing themes works.
- [ ] Inline code, bold and bullet lists render. URL and project-file links
      open only on click. Headings, images, tables and HTML stay plain text;
      no image or other remote content loads when the card appears.
- [ ] With preview tabs enabled, story files use the preview tab; with them
      disabled, they open as normal tabs. Navigate between files while typing
      to check that edited files stay open.
- [ ] In IntelliJ, Visual Studio and ReSharper keymaps, press
      **Ctrl+Alt+Shift+Z**, release, then **Right** / **Left** / **End** to run
      Next Step / Previous Step / Stop Story. Change each shortcut in Keymap
      and confirm that the new shortcut works.

## Anchoring

Repeat these checks in Rider (C#) and IntelliJ IDEA (Java/Kotlin).

- [ ] A story whose start line is a few lines off is accepted. The report lists
      the correction, and the step opens at the right code.
- [ ] A story whose anchor text is not near its start line is rejected with
      `anchor-not-found`, and nothing is saved.
- [ ] While a story plays, add and remove lines above the current and later
      steps, in the editor and from outside the IDE. Prev and Next open the
      right lines, and the card and highlight stay on the range.
- [ ] While a story plays, edit the code of a later step, including its first
      line, then play that step. The tree shows "code changed" for the step,
      and the card shows a note.
- [ ] With no story playing, edit the code inside a step's range (not its first
      line) and click Refresh. The step shows "code changed".
- [ ] Delete the code of a step. The tree shows "lost"; the step plays at its
      stored lines, and the card warns "The code changed. This step can be wrong."
- [ ] The story row shows the number of lost and changed steps, and
      `code_story_list` reports the same status.
- [ ] Moved lines are saved in a local story file. A shared story file does not
      change (`git status` stays clean), but its steps still open at the moved code.
- [ ] After `git checkout` of another branch, stories find moved code again, and
      steps whose code is gone are lost.

## Questions and answers

Repeat these checks in Rider (C#) and IntelliJ IDEA (Java/Kotlin), in light and dark
themes, including a narrow tool window.

- [ ] The card’s **💬** button opens a small input below the step text. **Save Draft**
      and Ctrl+Enter save it; **Cancel** and Escape close it without saving. Repeat in
      Rider and IntelliJ IDEA, light/dark themes, and floating and tool-window cards.
- [ ] **Ctrl+Alt+Shift+Z**, then **C**, opens **Comment on Current Step**, including a
      dismissed card and a text-only step. The shortcut can be changed in Keymap.
- [ ] The collapsed **💬 N threads · N drafts** line expands inside the card. Drafts can
      be edited, deleted and submitted there. Long threads scroll, and expanding the
      card preserves code layout and keeps the input usable in narrow/split editors.
- [ ] Open-thread counts and unread dots appear in step rows when answers arrive.
      Open the threads to clear the dot; restart the IDE to check read state persists.
      Another IDE user has independent unread state; shared story files do not change.
- [ ] A question that exposes unclear wording can trigger `code_story_patch`, then
      `code_story_reply`. The answer shows the plugin’s **Story changes** line.
      Replacing the full story keeps threads by step ID; removed-step threads appear
      at story level with “step removed”.
- [ ] **Comment on step**, **Comment on story** and the context-menu **Comment** open an
      input with keyboard focus. Ctrl+Enter and **Save Draft** save a draft; Escape and
      **Cancel** discard the input. Drafts can be edited and deleted.
- [ ] The story row shows the draft count, and **Submit Round (N)** in the toolbar and the
      details pane counts all drafts of the story. Both are disabled without drafts.
- [ ] Drafts survive an IDE restart and are never written to the story file. An unsaved
      input stays while the story reloads and returns after looking at another step.
- [ ] After Submit, the notification and the details pane show
      "Waiting for your agent: tell it /code-story feedback"; both copy buttons put the
      right command on the clipboard. **Withdraw** returns the comment to the drafts.
- [ ] `/code-story feedback` in the agent fetches the comments: they show "Agent working"
      and can no longer be withdrawn. The agent answers without code changes, and a
      notification with **Open** shows the story with the answers.
- [ ] With a story playing, answers arrive without moving the editor, the card or the step.
- [ ] **Reply** opens the thread again in the next round, and `/code-story feedback`
      answers only the new comment. **Resolve** hides the thread until **Show resolved**.
- [ ] Comments on a shared story change that shared file; `git diff` shows only the threads.
- [ ] Long comments and answers wrap to the width of the tool window, and comment text
      shows as plain text: HTML in a comment stays visible as text.

## Change information

Repeat these checks in Rider (C#) and IntelliJ IDEA (Java/Kotlin), in light
and dark themes.

- [ ] With uncommitted work, ask the agent for a story about the change. The
      saved story has `baseRef` set to the full SHA of `HEAD`, and the story
      details show the diff base.
- [ ] Steps on new code show **A**, steps on changed code show **M**, and
      steps on unchanged code show no badge. The badges are readable in light
      and dark themes. Commit the work and click Refresh: the badges stay.
- [ ] **Show diff** in the step details and in the context menu opens the IDE
      diff viewer with the base on the left and the current file on the right,
      scrolled to the step's lines. A new file has an empty left side; a
      renamed file is compared with its old path.
- [ ] An exploration story without `baseRef` shows no badges and no
      **Show diff**. A story whose `baseRef` Git does not know gets the
      `unknown-base-ref` warning, shows no badges and no **Show diff**.
- [ ] With the Git plugin disabled, a story with `baseRef` still saves and
      plays, and shows no change information.

After the tag workflow finishes, download the release asset and repeat
the install and open checks in a fresh test profile.
