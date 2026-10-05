# Manual release checklist

Before tagging a version, install `build/distributions/*.zip` from disk in
Rider (a C# solution) and IntelliJ IDEA (a Java or Kotlin project). Repeat
the UI checks in light and dark themes. Record the IDE versions used.

- [ ] The plugin installs and enables without errors.
- [ ] **View | Tool Windows | Code Story** opens an empty window.
- [ ] On a fresh IDE profile, the window is docked on the right.
- [ ] The IDE's normal controls can dock it left or bottom, or float it.
- [ ] Closing and reopening the window works.
- [ ] The window is available while the IDE indexes the project.
- [ ] With MCP Server and Git plugins disabled in a test profile, Code Story
      still loads and the window opens.
- [ ] The IDE log has no Code Story loading or runtime errors.
- [ ] CI build, checks and all eight Plugin Verifier jobs pass.
- [ ] The release tag matches `version` in `gradle.properties`.

After the tag workflow finishes, download the release asset and repeat
the install and open checks in a fresh test profile.
