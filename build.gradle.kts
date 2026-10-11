import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.productInfo
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
}

val riderBaselineVersion = providers.gradleProperty("riderBaselineVersion")
val riderBaselineBuild = providers.gradleProperty("riderBaselineBuild")
val riderVersion = providers.gradleProperty("riderVersion")
val riderBuild = providers.gradleProperty("riderBuild")

// Local distributions are a download-saving override, not another compatibility target.
fun requirePinnedRider(directory: File, expectedVersion: String, expectedBuild: String): File {
    require(directory.isDirectory) { "Local Rider path is not an IDE directory: $directory" }
    // The product metadata is in Contents/Resources inside macOS IDE bundles.
    val platformDirectory = directory.resolve("Contents").takeIf { it.isDirectory } ?: directory
    val info = platformDirectory.toPath().productInfo()
    require(info.productCode == "RD" && info.version == expectedVersion &&
        info.buildNumber == expectedBuild && info.versionSuffix.isNullOrBlank()) {
        "Expected Rider $expectedVersion (RD-$expectedBuild), got " +
            "${info.productCode} ${info.version} (${info.buildNumber}) ${info.versionSuffix.orEmpty()} at $directory"
    }
    return directory
}

val localRiderBaselinePath = providers.gradleProperty("localRiderBaselinePath").map {
    requirePinnedRider(file(it), riderBaselineVersion.get(), riderBaselineBuild.get())
}
val localIdePath = providers.gradleProperty("localIdePath").map {
    requirePinnedRider(file(it), riderVersion.get(), riderBuild.get())
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        if (localRiderBaselinePath.isPresent) {
            local(localRiderBaselinePath.get())
        } else {
            rider(riderBaselineVersion.get())
        }
        // Optional at runtime (see plugin.xml); kotlinx.serialization comes from the IDE.
        bundledPlugin("com.intellij.mcpServer")
        bundledPlugin("Git4Idea")
        // Compile fixtures against the baseline only. Each testIde entry resolves its
        // own current-Rider test framework and runtime classpath below.
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Baseline SDK bytecode and Kotlin API, not the Java version of its bundled JBR.
        jvmTarget = JvmTarget.JVM_21
        apiVersion = KotlinVersion.KOTLIN_2_3
        languageVersion = KotlinVersion.KOTLIN_2_3
    }
}

tasks.withType<JavaCompile>().configureEach { options.release = 21 }

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = riderBaselineBuild
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        useBundledRuntime = true
        ides {
            if (localIdePath.isPresent) {
                local(localIdePath.get())
            } else {
                create(IntelliJPlatformType.Rider, riderVersion.get())
            }
        }
    }
}

intellijPlatformTesting.runIde.register("runLocalIde") {
    type = IntelliJPlatformType.Rider
    version = riderVersion
    useInstaller = true
    localPath.fileProvider(localIdePath)
}

val testCurrentRider = intellijPlatformTesting.testIde.register("testCurrentRider") {
    type = IntelliJPlatformType.Rider
    version = riderVersion
    useInstaller = true
    localPath.fileProvider(localIdePath)
    // Stable published 262 framework: the exact Rider build has no matching artifact.
    // Pin this rather than allowing CLOSEST_VERSION to select a newer snapshot.
    testFramework(TestFrameworkType.Platform, "262.10968.136")
    plugins {
        bundledPlugin("com.intellij.mcpServer")
        bundledPlugin("Git4Idea")
    }
    task {
        // No filter: all core and IDE tests. TestIdeTask supplies instrumented tests,
        // its own current-SDK classpath and the bundled current Rider JBR (Java 25).
        // Do not replace its JavaLauncher or copy the baseline test runtime classpath.
        systemProperty("codeStory.expectedRiderBuild", riderBuild.get())
        maxParallelForks = 1
    }
}

val testWithoutMcp = intellijPlatformTesting.testIde.register("testWithoutMcp") {
    type = IntelliJPlatformType.Rider
    version = riderVersion
    useInstaller = true
    localPath.fileProvider(localIdePath)
    testFramework(TestFrameworkType.Platform, "262.10968.136")
    plugins {
        bundledPlugin("Git4Idea")
        disablePlugin("com.intellij.mcpServer")
    }
    task {
        maxParallelForks = 1
        filter {
            includeTestsMatching("*StoryFileDropTest")
            includeTestsMatching("*RiderRuntimeTest")
        }
        systemProperty("codeStory.expectedRiderBuild", riderBuild.get())
        systemProperty("codeStory.testWithoutMcp", "true")
        mustRunAfter(testCurrentRider)
    }
}

// The default test task would repeat the full suite on the compilation baseline.
// Delegate to the unfiltered current-Rider suite instead; `test` is not a no-op.
tasks.test {
    enabled = false
    dependsOn(testCurrentRider)
}
tasks.check { dependsOn(testCurrentRider, testWithoutMcp) }

tasks.named<VerifyPluginTask>("verifyPlugin") {
    // Verify the exact ZIP that CI uploads and the release job publishes.
    archiveFile = tasks.named<BuildPluginTask>("buildPlugin").flatMap { it.archiveFile }
}

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
