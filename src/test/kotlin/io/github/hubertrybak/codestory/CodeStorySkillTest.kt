package io.github.hubertrybak.codestory

import io.github.hubertrybak.codestory.core.StoryFormat
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CodeStorySkillTest {
    @Test
    fun `installs and updates both agents at user and project level`() {
        val temporary = Files.createTempDirectory("code-story-skill")
        try {
            val user = temporary.resolve("user")
            val project = temporary.resolve("project")
            assertTrue(CodeStorySkill.installed(user, project).isEmpty())
            for (base in listOf(user, project)) {
                val directories = CodeStorySkill.install(base)
                assertEquals(CodeStorySkill.directories(base), directories)
                for (dir in directories) {
                    for (name in CodeStorySkill.files) {
                        val bundled = javaClass.getResourceAsStream("/code-story/skill/$name")!!.bufferedReader().use { it.readText() }
                        assertEquals(name, bundled, Files.readString(dir.resolve(name)))
                    }
                    assertTrue(StoryFormat.decode(Files.readString(dir.resolve("references/example.story.json"))) is StoryFormat.Decoded.Valid)
                    Files.writeString(dir.resolve("version.txt"), "0.0.0\n")
                    Files.writeString(dir.resolve("SKILL.md"), "Old skill")
                    Files.delete(dir.resolve("references/guide.md"))
                    Files.writeString(dir.resolve("my-notes.txt"), "Keep this file")
                }
            }
            val old = CodeStorySkill.installed(user, project)
            assertEquals(4, old.size)
            assertTrue(old.all { it.needsUpdate(CodeStorySkill.version) && !it.complete })
            CodeStorySkill.update(old)
            val updated = CodeStorySkill.installed(user, project)
            assertTrue(updated.all { it.complete && it.version == CodeStorySkill.version && !it.needsUpdate(CodeStorySkill.version) })
            updated.forEach { assertEquals("Keep this file", Files.readString(it.directory.resolve("my-notes.txt"))) }
            Files.walk(temporary).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().endsWith(".tmp") }) }
        } finally { temporary.toFile().deleteRecursively() }
    }

    @Test
    fun `manual and bundled workflows and references match`() {
        for (name in CodeStorySkill.files - "version.txt") {
            val bundled = javaClass.getResourceAsStream("/code-story/skill/$name")!!.bufferedReader().use { it.readText() }
            assertEquals(name, Files.readString(java.nio.file.Path.of("skills/code-story/$name")), bundled)
        }
        val version = javaClass.getResourceAsStream("/code-story/version.txt")!!.bufferedReader().use { it.readText() }.trim()
        assertEquals(version, CodeStorySkill.version)
        // A manual copy of the skill must not look outdated next to the plugin of the same release.
        assertEquals("skills/code-story/version.txt", version, Files.readString(java.nio.file.Path.of("skills/code-story/version.txt")).trim())
    }

    @Test
    fun `does not install through symlinks`() {
        val temporary = Files.createTempDirectory("code-story-skill-link")
        try {
            val elsewhere = Files.createDirectory(temporary.resolve("elsewhere"))
            Files.createSymbolicLink(temporary.resolve(".agents"), elsewhere)
            assertThrows(java.io.IOException::class.java) { CodeStorySkill.install(temporary) }
            Files.list(elsewhere).use { assertEquals(0L, it.count()) }
        } finally { temporary.toFile().deleteRecursively() }
    }
}
