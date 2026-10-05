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

After the tag workflow finishes, download the release asset and repeat
the install and open checks in a fresh test profile.
