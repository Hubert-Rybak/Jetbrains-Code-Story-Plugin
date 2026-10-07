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
- [ ] The release tag matches `version` in `gradle.properties`.

## Agent stories

Switch on the MCP server (Settings | Tools | MCP Server), use Auto-Configure
for the agent, and install `skills/code-story` by hand.

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

## Change information

Repeat these checks in Rider (C#) and IntelliJ IDEA (Java/Kotlin).

- [ ] Ask the agent for a story about uncommitted work. The saved story has
      `baseRef` set to the full SHA of `HEAD`, and the details show the diff base.
- [ ] Steps on new code show **A**, steps on changed code show **M**, and steps
      on unchanged code show no badge. The badges stay the same after a commit.
- [ ] **Show diff** in the step details and in the context menu opens the IDE
      diff viewer with the base revision against the current file, at the step's
      lines. A file that is new since the base has an empty left side.
- [ ] A story with a `baseRef` that Git does not know is saved with an
      `unknown-base-ref` warning, and shows no badges and no **Show diff**.
- [ ] Exploration and bug-trace stories have no `baseRef`, no badges and no
      **Show diff**.
- [ ] With the Git plugin disabled, a story with `baseRef` saves and plays
      without badges, **Show diff** or errors in the IDE log.

After the tag workflow finishes, download the release asset and repeat
the install and open checks in a fresh test profile.
