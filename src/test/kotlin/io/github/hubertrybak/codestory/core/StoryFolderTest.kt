package io.github.hubertrybak.codestory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

class StoryFolderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val date = Instant.parse("2026-10-05T12:00:00Z")
    private val story = Story(
        "Explain the payment flow",
        StoryType.CHANGE,
        "The controller takes the request.",
        "Explain the payment flow",
        StoryAuthor("Test Agent", "test-model"),
        listOf(Step("Entry point", "The controller takes the request.", Location("src/App.kt", 2, 4), "entry")),
        createdAt = date,
    )

    private fun folder(): StoryFolder = StoryFolder(temp.root.toPath())

    private fun saved(result: StoryFolder.SaveResult): StoryFolder.SaveResult.Saved =
        result as? StoryFolder.SaveResult.Saved ?: throw AssertionError("not saved: $result")

    @Test
    fun `saves the story as a dated file in the local folder`() {
        val folder = folder()
        val result = saved(folder.save(story, date))

        assertEquals("2026-10-05-explain-the-payment-flow", result.id)
        assertEquals(folder.localDir.resolve("2026-10-05-explain-the-payment-flow.story.json"), result.file)
        assertEquals(".codestory/local/2026-10-05-explain-the-payment-flow.story.json", folder.relativePath(result.file))
        assertEquals(StoryFormat.encode(story, result.id), result.file.readText())
    }

    @Test
    fun `local folder ignores everything in Git`() {
        val folder = folder()
        folder.save(story, date)

        val lines = folder.localDir.resolve(".gitignore").readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(listOf("*"), lines)
    }

    @Test
    fun `leaves no temporary files`() {
        val folder = folder()
        folder.save(story, date)
        folder.save(story, date)

        assertEquals(
            listOf(".gitignore", "2026-10-05-explain-the-payment-flow-2.story.json", "2026-10-05-explain-the-payment-flow.story.json"),
            folder.localDir.listDirectoryEntries().map { it.name }.sorted(),
        )
    }

    @Test
    fun `adds a number when the name exists`() {
        val folder = folder()
        val ids = (1..3).map { saved(folder.save(story, date)).id }
        assertEquals(
            listOf("2026-10-05-explain-the-payment-flow", "2026-10-05-explain-the-payment-flow-2", "2026-10-05-explain-the-payment-flow-3"),
            ids,
        )
    }

    @Test
    fun `keeps IDs unique across the local and shared folders`() {
        val shared = Files.createDirectories(temp.root.toPath().resolve(".codestory/shared"))
        Files.writeString(shared.resolve("2026-10-05-explain-the-payment-flow.story.json"), "{}")

        assertEquals("2026-10-05-explain-the-payment-flow-2", saved(folder().save(story, date)).id)
    }

    @Test
    fun `story ID comes from the file name when a file is renamed`() {
        val folder = folder()
        val original = saved(folder.save(story, date))
        Files.move(original.file, folder.localDir.resolve("2026-10-05-renamed.story.json"))
        assertEquals("2026-10-05-renamed", folder.list().single().id)
    }

    @Test
    fun `rejects a story file larger than 256 KB and saves nothing`() {
        val folder = folder()
        val longComment = "word ".repeat(StoryLimits.MAX_FILE_BYTES / 5)
        val large = story.copy(title = "Large", steps = listOf(Step("Step", longComment, Location("src/App.kt", 1, 1), "entry")))

        val result = folder.save(large, date)

        assertTrue(result is StoryFolder.SaveResult.TooLarge)
        assertTrue((result as StoryFolder.SaveResult.TooLarge).bytes > StoryLimits.MAX_FILE_BYTES)
        assertFalse(Files.exists(folder.localDir))
        val report = ReportItem.storyTooLarge(result.bytes)
        assertEquals(ErrorCode.STORY_TOO_LARGE.code, report.code)
        assertEquals(null, report.step)
    }

    @Test
    fun `a story of exactly 256 KB is saved`() {
        val folder = folder()
        val large = story.copy(title = "Large", steps = listOf(Step("Step", "x", Location("src/App.kt", 1, 1), "entry")))
        val base = StoryFormat.encode(large, "2026-10-05-large")
        val comment = "x".repeat(StoryLimits.MAX_FILE_BYTES - base.toByteArray().size + 1)
        val result = saved(folder.save(large.copy(steps = listOf(large.steps.single().copy(comment = comment))), date))

        assertEquals(StoryLimits.MAX_FILE_BYTES.toLong(), Files.size(result.file))
    }

    @Test
    fun `lists stories by creation time even when an older file is modified later`() {
        val folder = folder()
        val first = saved(folder.save(story, date))
        val second = saved(folder.save(story.copy(title = "Bug trace"), date.plusSeconds(60)))
        Files.setLastModifiedTime(first.file, FileTime.fromMillis(2_000_000))
        Files.setLastModifiedTime(second.file, FileTime.fromMillis(1_000_000))

        val stories = folder.list()

        assertEquals(listOf(second.id, first.id), stories.map { it.id })
        assertEquals(story, stories.last().story)
        assertEquals("2026-10-05", stories.first().date)
        assertEquals(folder.root, stories.first().root)
    }

    @Test
    fun `skips files that are not valid stories`() {
        val folder = folder()
        val good = saved(folder.save(story, date))
        val local = folder.localDir
        Files.writeString(local.resolve("broken.story.json"), "{ not json")
        Files.writeString(local.resolve("future.story.json"), StoryFormat.encode(story, "future").replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
        Files.writeString(local.resolve("no-steps.story.json"), """{"schemaVersion": 1, "id": "no-steps", "title": "T", "steps": []}""")
        Files.writeString(local.resolve("large.story.json"), " ".repeat(StoryLimits.MAX_FILE_BYTES + 1))
        Files.writeString(local.resolve("notes.json"), StoryFormat.encode(story, "notes"))
        val skipped = mutableListOf<String>()

        val stories = folder.list { file, _ -> skipped += file.name }

        assertEquals(listOf(good.id), stories.map { it.id })
        assertEquals(listOf("broken.story.json", "future.story.json", "large.story.json", "no-steps.story.json"), skipped.sorted())
    }

    @Test
    fun `lists nothing when there is no story folder`() {
        assertEquals(emptyList<StoredStory>(), folder().list())
    }

    @Test
    fun `replacement keeps the file name and original creation metadata`() {
        val folder = folder()
        val git = StoryGit("original", "abc123")
        val first = saved(folder.save(story, date, git))
        val revised = story.copy(title = "A new title", summary = "The service saves the result.")
        val result = saved(folder.save(revised, date.plusSeconds(3600), StoryGit("other", "def456"), first.id))

        assertEquals(first.id, result.id)
        assertEquals(first.file, result.file)
        assertEquals(revised.copy(createdAt = date, git = git), result.story)
        assertEquals(StoryFormat.encode(result.story, first.id), Files.readString(first.file))
        assertEquals(listOf(first.id), folder.list().map { it.id })
        assertEquals(2, folder.localDir.listDirectoryEntries().size)
    }

    @Test
    fun `gets and replaces a shared story in place`() {
        val folder = folder()
        val first = saved(folder.save(story, date))
        Files.createDirectories(folder.sharedDir)
        val shared = folder.sharedDir.resolve(first.file.fileName)
        Files.move(first.file, shared)
        assertEquals(shared, folder.get(first.id)!!.file)
        val result = saved(folder.save(story.copy(title = "Shared revision"), date.plusSeconds(60), storyId = first.id))
        assertEquals(shared, result.file)
        assertEquals("shared", folder.list().single().folder)
        assertFalse(Files.exists(first.file))
        assertEquals(listOf(".gitignore"), folder.localDir.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `sharing never overwrites a shared file with the same name`() {
        val folder = folder()
        val saved = saved(folder.save(story, date))
        val stored = folder.get(saved.id)!!
        val shared = Files.createDirectories(folder.sharedDir).resolve(saved.file.fileName)
        Files.writeString(shared, "Existing reviewer story")
        assertTrue(runCatching { folder.setShared(stored, true) }.exceptionOrNull() is java.nio.file.FileAlreadyExistsException)
        assertEquals("Existing reviewer story", shared.readText())
        assertEquals(StoryFormat.encode(saved.story, saved.id), saved.file.readText())
    }

    @Test
    fun `management rejects storage symlinks and stale paths`() {
        val folder = folder()
        val saved = saved(folder.save(story, date))
        val stored = folder.get(saved.id)!!
        val outside = temp.newFolder("outside-share").toPath()
        Files.createSymbolicLink(folder.sharedDir, outside)
        assertTrue(runCatching { folder.setShared(stored, true) }.exceptionOrNull() is java.io.IOException)
        assertTrue(Files.exists(saved.file))
        assertTrue(outside.listDirectoryEntries().isEmpty())
        Files.delete(folder.sharedDir)
        val moved = folder.setShared(stored, true)
        assertFalse(folder.delete(stored))
        assertTrue(Files.exists(moved.file))
        assertTrue(folder.delete(moved))
    }

    @Test
    fun `moved anchors are written only into the local story that was checked`() {
        val folder = folder()
        val first = saved(folder.save(story, date))
        val moved = first.story.copy(steps = first.story.steps.map { it.copy(location = it.location!!.copy(startLine = 5, endLine = 7)) })

        assertTrue(folder.updateAnchors(first.id, first.story, moved))
        assertEquals(StoryFormat.encode(moved, first.id), Files.readString(first.file))
        // The file now holds another version, for example a revision by an agent.
        assertFalse(folder.updateAnchors(first.id, first.story, moved.copy(title = "Old check")))
        assertEquals(moved, folder.get(first.id)!!.story)
        assertFalse(folder.updateAnchors("2026-10-05-missing", first.story, moved))

        Files.createDirectories(folder.sharedDir)
        val shared = Files.move(first.file, folder.sharedDir.resolve(first.file.fileName))
        val before = Files.readString(shared)
        assertFalse(folder.updateAnchors(first.id, moved, first.story))
        assertEquals(before, Files.readString(shared))
    }

    @Test
    fun `unknown and unsafe IDs cannot create or read files`() {
        val folder = folder()
        for (id in listOf("2026-10-05-missing", "../outside", "/tmp/story", "C:\\outside", "2026-10-05-a/../../outside")) {
            assertNull(folder.get(id))
            assertTrue(folder.save(story, date, storyId = id) is StoryFolder.SaveResult.NotFound)
        }
        assertFalse(Files.exists(folder.localDir))
    }

    @Test
    fun `oversized replacement leaves the original file untouched`() {
        val folder = folder()
        val first = saved(folder.save(story, date))
        val before = Files.readString(first.file)
        val large = story.copy(steps = listOf(story.steps.single().copy(comment = "word ".repeat(60_000))))
        assertTrue(folder.save(large, date, storyId = first.id) is StoryFolder.SaveResult.TooLarge)
        assertEquals(before, Files.readString(first.file))
        assertEquals(2, folder.localDir.listDirectoryEntries().size)
    }

    @Test
    fun `storage symlinks do not expose or overwrite outside stories`() {
        val repo = temp.newFolder("repo").toPath()
        val outside = temp.newFolder("outside").toPath()
        val folder = StoryFolder(repo)
        val id = "2026-10-05-outside"
        val external = outside.resolve("$id.story.json")
        val before = StoryFormat.encode(story, id)
        Files.writeString(external, before)
        Files.createDirectories(repo.resolve(".codestory"))
        Files.createSymbolicLink(folder.localDir, outside)

        assertTrue(folder.list().isEmpty())
        assertNull(folder.get(id))
        assertTrue(folder.save(story, date, storyId = id) is StoryFolder.SaveResult.NotFound)
        assertTrue(runCatching { folder.save(story, date) }.exceptionOrNull() is java.io.IOException)
        assertEquals(before, Files.readString(external))
        assertEquals(listOf("$id.story.json"), outside.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `story root is the Git repository root`() {
        val repo = temp.newFolder("repo").toPath()
        Files.createDirectories(repo.resolve(".git"))
        val project = Files.createDirectories(repo.resolve("src/solution"))

        assertEquals(repo, StoryFolder.findRoot(project))
        assertEquals(repo, StoryFolder.forProject(project).root)
        assertEquals(repo.resolve(".codestory/local"), StoryFolder.forProject(project).localDir)
    }

    @Test
    fun `a git file marks a worktree root`() {
        val worktree = temp.newFolder("worktree").toPath()
        Files.writeString(worktree.resolve(".git"), "gitdir: /elsewhere/.git/worktrees/one\n")

        assertEquals(worktree, StoryFolder.findRoot(worktree.resolve("module")))
    }

    @Test
    fun `story root is the project folder without Git`() {
        val project: Path = temp.newFolder("plain").toPath()
        assertEquals(project, StoryFolder.findRoot(project))
    }

    @Test
    fun `slug uses lowercase words joined by dashes`() {
        assertEquals("explain-the-payment-flow", StoryFolder.slug("Explain the payment flow"))
        assertEquals("why-orderservice-retry-retries-twice", StoryFolder.slug("  Why `OrderService.retry()` retries... twice? "))
        assertEquals("cafe-resume", StoryFolder.slug("Café résumé"))
        assertEquals("story", StoryFolder.slug("!!!"))
        assertEquals("story", StoryFolder.slug("日本語"))
    }

    @Test
    fun `long slugs stop at a word boundary`() {
        val slug = StoryFolder.slug("word ".repeat(30))
        assertTrue(slug.length <= 60)
        assertTrue(slug.split('-').all { it == "word" })
        assertEquals(60, StoryFolder.slug("x".repeat(100)).length)
    }
}
