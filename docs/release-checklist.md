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
- [ ] Play resumes the last step after Stop; Restart starts at step 1. Visited
      marks last for the current IDE session, and reading position is not
      written to the story file.
- [ ] Closing the target editor or project removes its floating card without
      leaving an orphan card or errors in the IDE log.

After the tag workflow finishes, download the release asset and repeat
the install and open checks in a fresh test profile.
