# Code Story

A JetBrains plugin for guided code walkthroughs, with Rider as the primary
target. Requires Rider or IntelliJ IDEA 2025.3 or newer.

The initial skeleton provides an empty **Code Story** tool window. See
[spec.md](spec.md) for the product specification and
[issue #1](https://github.com/Hubert-Rybak/Jetbrains-Code-Story-Plugin/issues/1)
for the implementation roadmap.

## Install

Download the plugin zip from [GitHub Releases](https://github.com/Hubert-Rybak/Jetbrains-Code-Story-Plugin/releases).
In the IDE, open **Settings | Plugins | gear icon | Install Plugin from Disk**,
select the zip, and restart if prompted. Open **View | Tool Windows | Code Story**.
The window starts on the right; use the IDE's normal tool window controls to move it.

The MCP Server and Git plugins are optional. This skeleton does not use them yet.

## Develop

This single-module Kotlin project is based on the
[official IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template/tree/b7b700870b23e1323353a0350a1acd9cc6d2b225).
It compiles against IntelliJ IDEA 2025.3 with a Java 21 toolchain. Kotlin's
language and API versions match the 2.2 standard library in that IDE;
the plugin does not bundle its own standard library.

Use JDK 21 or newer to run Gradle. The toolchain resolver downloads JDK 21
if it is missing. Import the repository as a Gradle project, or run:

```sh
./gradlew check buildPlugin verifyPluginStructure
# Use your installed Rider in a separate development sandbox.
./gradlew runLocalIde -PlocalIdePath="/path/to/Rider.app"
```

The installable zip is in `build/distributions/`. `check` runs the unit test
task; this skeleton has no story logic or unit tests yet. The platform test
framework and JUnit are configured for subsequent issues.

```sh
# Full compatibility matrix: Rider and IDEA, three releases and latest EAP.
./gradlew verifyPlugin
# One target, useful during development.
./gradlew verifyPlugin -PverificationType=RD -PverificationVersion=2025.3
# Verify against an installed IDE without downloading another copy.
./gradlew verifyPlugin -PlocalIdePath="/path/to/Rider.app"
```

`localIdePath` changes the launch and verification targets only. Compilation
keeps using the 2025.3 SDK, so local development does not raise the minimum
supported IDE version. `./gradlew runIde` launches the baseline IDEA sandbox.

## Release

CI builds the zip, runs `check`, checks the plugin structure, and runs the
Plugin Verifier in eight jobs: Rider and IDEA 2025.3, 2026.1, 2026.2 and the
latest EAP for each product. Reports and the zip are workflow artifacts.

Set `version` in `gradle.properties`, complete the
[manual checklist](docs/release-checklist.md), and push a matching tag
(for example, `v0.1.0`). The same workflow publishes the verified build's zip
to GitHub Releases after every build and verifier job passes. A tag that
does not match the configured version fails the build. Release publication
uses the workflow's `GITHUB_TOKEN`; no Marketplace credentials are needed.

Licensed under [Apache-2.0](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md).
