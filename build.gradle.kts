import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
}

val localIdePath = providers.gradleProperty("localIdePath")

dependencies {
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        intellijIdea("2025.3")
        // Optional at runtime (see plugin.xml); kotlinx.serialization comes from the IDE.
        bundledPlugin("com.intellij.mcpServer")
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Match the Kotlin API bundled with the oldest supported IDE.
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
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

tasks.jar {
    from(files("LICENSE", "THIRD_PARTY_NOTICES.md")) { into("META-INF") }
}
