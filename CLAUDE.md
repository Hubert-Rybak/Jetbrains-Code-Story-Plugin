# Agent notes

Notes for coding agents in this repository. [CONTRIBUTING.md](CONTRIBUTING.md) describes
the build, the tests and the release; this file adds what an agent needs to work on the
plugin, especially in a cloud container without a screen.

## Build and test

- Run Gradle with a UTF-8 locale. Some test names contain typographic apostrophes, and the
  test report fails without it:
  `LC_ALL=C.UTF-8 LANG=C.UTF-8 ./gradlew check`
- One test class: `./gradlew test --tests 'io.github.hubertrybak.codestory.StoryTimelineTest'`.
  Add `--rerun` to run unchanged tests again, and `-i` to see their output. The results are
  in `build/test-results/test/TEST-*.xml`.
- Plugin Verifier against the IDE that Gradle downloaded, without another download:
  `./gradlew verifyPlugin -PlocalIdePath=$(find ~/.gradle/caches -maxdepth 6 -type d -name 'idea-2025.3' | head -1)`.
  The plugin must not use deprecated, experimental or internal platform API.
- Maven Central can answer with HTTP 429. Run the build again.

## Run the plugin in a sandbox IDE

Tests do not show layout, colors or focus. For a UI change, also run the plugin in the
sandbox IDE of `./gradlew runIde` and look at screenshots. The helpers are in
[`tools/ide-sandbox`](tools/ide-sandbox). In the commands, `SCRATCH` is a folder for
temporary files outside the repository, for example the scratchpad of your session. In a
container without a screen:

1. **Start a virtual display.** `Xvfb :99 -screen 0 1920x1080x24 -nolisten tcp &`.
   For sharp screenshots, use `3840x2160` and set `SANDBOX_SCALE=2` in step 4.
2. **Create the demo project** outside the repository, for example in your scratch folder:
   `tools/ide-sandbox/make-demo.sh "$SCRATCH/payments"`. It is a Git repository with a
   story of every step kind in `.codestory/local/`. The IDE plays the story when the project
   opens.
3. **Optional: preset settings.** The sandbox reads its settings from
   `.intellijPlatform/sandbox/code-story/IU-2025.3/config_runIde/options/` at startup
   (the first `runIde` creates the folder; you can also create it). `codeStory.xml` sets
   the Code Story options, and `laf.xml` sets the theme (`Islands Light` or
   `Islands Dark`):

   ```xml
   <application>
     <component name="CodeStorySettings">
       <option name="bringToFront" value="false" />
       <option name="setupNotificationShown" value="true" />
       <option name="storyView" value="TIMELINE" />
     </component>
   </application>
   ```

   ```xml
   <application>
     <component name="LafManager" autodetect="false">
       <laf themeId="Islands Light" />
     </component>
   </application>
   ```

4. **Start the IDE** in the background (a few minutes for the first build):

   ```sh
   DISPLAY=:99 SANDBOX_PROJECT="$SCRATCH/payments" LC_ALL=C.UTF-8 LANG=C.UTF-8 \
     ./gradlew --init-script tools/ide-sandbox/runide.init.gradle runIde > "$SCRATCH/runide.log" 2>&1
   ```

   The init script opens the project, trusts it and turns off the tips. The screen stays
   black until a window shows, so poll with screenshots, for example until
   `convert shot.png -format "%[fx:mean]" info:` is above 0.05.
5. **First start in a new container.** The IDE shows the **JetBrains User Agreement**.
   Accepting it is the user's decision: ask the user, and click through only after they
   agree. Never use options that skip the agreement. Select the check box at the bottom of
   the dialog, then click **Continue**. On **Data Sharing**, click **Don't Send**. The IDE
   stores both answers outside the repository (`~/.java/.userPrefs/jetbrains` and
   `~/.local/share/JetBrains/consentOptions`), so a new container asks again.

   Every start also shows balloons: a warning about `JAVA_TOOL_OPTIONS`, which the
   container sets (**Don't show again**), the Code Story reminder to switch on the MCP
   server, and "IDE error occurred" for the startup noise in step 8. Most of them hide
   after a few seconds.
6. **Take a screenshot.** `DISPLAY=:99 import -window root "$SCRATCH/shot.png"` (ImageMagick),
   then look at it with the Read tool. To crop, use
   `convert shot.png -crop WIDTHxHEIGHT+X+Y +repage part.png`. Without `+repage`, the next
   crop of that file uses the coordinates of the full screen.
7. **Click and type.** `DISPLAY=:99 java tools/ide-sandbox/Robo.java click X Y`. The
   coordinates are pixels of the screenshot. The file lists the other commands: `dclick`,
   `move`, `drag`, `wheel`, `keys` and `type`. `click` moves the mouse onto the target
   first, because small buttons, such as the buttons of the step card, react only after a
   hover. A balloon shows its close button when the mouse is on it.
8. **Read the log.** `.intellijPlatform/sandbox/code-story/IU-2025.3/log_runIde/idea.log`.
   The sandbox runs in internal mode, so every logged error also shows an
   "IDE error occurred" balloon. Known noise: `SlowOperations` errors from
   `StepNavigator.open` when a story plays while the project indexes at startup, and errors
   from the bundled Kubernetes plugin.
9. **Stop the IDE** with `kill $(pgrep -f '[c]om.intellij.idea.Main')`. The brackets stop
   the pattern from matching your own shell; `pkill -f` with a pattern that is also in your
   command kills the shell (exit code 144). Gradle then reports exit code 143 for `runIde`,
   which is expected. The IDE does not save settings that you change in its UI before the
   kill. Also stop Xvfb, and remove preset settings that later runs must not use.

A code change needs a new `runIde`. To check a layout faster, render the panel in a
temporary test instead: create the panel in a `HeavyPlatformTestCase`, set its size, call
`doLayout()` on it and on every child (`validate()` does nothing without a window), paint
it into a `BufferedImage` and write a PNG. Delete the test before you commit.

### Screenshots for the documentation

The timeline screenshots in [`docs/images`](docs/images) come from this setup: the demo
project at `SANDBOX_SCALE=2` on a `3840x2160` display, once with `Islands Light` and once
with `Islands Dark`. Text with subpixel antialiasing gets colored edges when GitHub scales
an image, so also preset grayscale antialiasing in `other.xml`:

```xml
<application>
  <component name="NotRoamableUiSettings">
    <option name="ideAAType" value="GREYSCALE" />
    <option name="editorAAType" value="GREYSCALE" />
  </component>
</application>
```

- Drag the splitter between the Project tool window and the editor to set the width of the
  editor, then crop the Project tool window away. A crop of about 1000 logical pixels stays
  readable at the width of the README.
- The mouse wheel scrolls the timeline in large steps. For an exact position, hover the
  scroll bar and drag its thumb a few pixels.
- A single click on a line of code leaves a caret in the snippet. A double-click opens the
  line and moves the keyboard focus to the editor.
- Crop the same rectangle from the light and the dark screenshot, so the two images of a
  pair match.

## Pitfalls

- In headless tests, `ToolWindow.show(Runnable)` never runs its callback. Publish an event
  after `show()`, or keep a request that the panel takes when it opens.
- A text component scrolls its caret into view in a later event when its text changes. In a
  panel that scrolls, read-only text must keep its caret: use `PlainText(keepCaret = true)`;
  `StoryTextPane` does it already.
- In a `JComponent` subclass, Kotlin resolves `accessibleContext` to the protected field,
  which is `null` until it is created. Call `getAccessibleContext()`.
- An embedded editor reports a click only after its release, and the second click of a
  double-click can miss it while the event thread is busy. `CodeSnippet` listens to the AWT
  events instead and opens a double-click at its second press.
- A mouse press that a test sends to an embedded editor registers a hover listener that the
  leak check reports. Send `MOUSE_CLICKED`, or call the handler, for example
  `CodeSnippet.lineClicked`.
- Story text comes from files and agents. Show it as plain text (`PlainText`,
  `SimpleColoredComponent`) or through `StoryTextPane`, never as HTML. Set
  `html.disable` on labels and links that show paths.
