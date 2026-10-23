import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
    // The plugin's change notes are the CHANGELOG.md entry of its version.
    id("org.jetbrains.changelog")
}

val localIdePath = providers.gradleProperty("localIdePath")

dependencies {
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        intellijIdea("2025.3")
        // Optional at runtime (see plugin.xml); kotlinx.serialization comes from the IDE.
        bundledPlugin("com.intellij.mcpServer")
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Match the Kotlin API bundled with the oldest supported IDE.
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
        // Without bridges for inherited interface methods, classes do not call the deprecated and
        // experimental defaults of platform interfaces such as ToolWindowFactory.
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            if (localIdePath.isPresent) {
                local(localIdePath.get())
            } else {
                val types = providers.gradleProperty("verificationType").orNull
                    ?.let { listOf(IntelliJPlatformType.fromCode(it)) }
                    ?: listOf(IntelliJPlatformType.IntellijIdea, IntelliJPlatformType.Rider)
                val versions = providers.gradleProperty("verificationVersion").orNull
                    ?.let { listOf(it) } ?: listOf("2025.3", "2026.1", "2026.2", "EAP")
                for (type in types) {
                    for (version in versions) {
                        if (version == "EAP") {
                            latest {
                                this.types = listOf(type)
                                channels = listOf(ProductRelease.Channel.EAP)
                            }
                        } else {
                            create(type, version)
                        }
                    }
                }
            }
        }
    }
}

intellijPlatformTesting.runIde.register("runLocalIde") {
    localPath.fileProvider(localIdePath.map { file(it) })
}

val testWithoutMcp = intellijPlatformTesting.testIde.register("testWithoutMcp") {
    testFramework(TestFrameworkType.Platform)
    plugins { disablePlugin("com.intellij.mcpServer") }
    task {
        filter {
            includeTestsMatching("*StoryFileDropTest")
            includeTestsMatching("*StoryFileWatcherReopenTest")
        }
        systemProperty("codeStory.testWithoutMcp", "true")
    }
}

tasks.check { dependsOn(testWithoutMcp) }

// Each release adds its CHANGELOG.md entry by hand. publishPlugin runs patchChangelog, which
// would drop the Unreleased entries, so the task is off.
tasks.patchChangelog { enabled = false }

tasks.jar {
    from(files("LICENSE", "THIRD_PARTY_NOTICES.md")) { into("META-INF") }
}

tasks.processResources {
    from("skills/code-story/references/guide.md") { into("code-story") }
    from("skills/code-story") { into("code-story/skill") }
    // The guide names the installed version; the IDE's plugin lookups are internal API from 2026.2.
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("code-story/version.txt") { expand("version" to pluginVersion) }
    filesMatching("code-story/skill/version.txt") { filter { pluginVersion } }
}
