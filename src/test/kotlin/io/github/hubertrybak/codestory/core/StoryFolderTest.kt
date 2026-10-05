package io.github.hubertrybak.codestory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.LocalDate
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

class StoryFolderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val date = LocalDate.of(2026, 10, 5)
    private val story = Story(
        "Explain the payment flow",
        listOf(Step("Entry point", "The controller takes the request.", Location("src/App.kt", 2, 4))),
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
    fun `rejects a story file larger than 256 KB and saves nothing`() {
        val folder = folder()
        val longComment = "word ".repeat(StoryLimits.MAX_FILE_BYTES / 5)
        val large = Story("Large", listOf(Step("Step", longComment, Location("src/App.kt", 1, 1))))

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
        val base = StoryFormat.encode(Story("Large", listOf(Step("Step", "x", Location("src/App.kt", 1, 1)))), "2026-10-05-large")
        val comment = "x".repeat(StoryLimits.MAX_FILE_BYTES - base.toByteArray().size + 1)
        val result = saved(folder.save(Story("Large", listOf(Step("Step", comment, Location("src/App.kt", 1, 1)))), date))

        assertEquals(StoryLimits.MAX_FILE_BYTES.toLong(), Files.size(result.file))
    }

    @Test
    fun `lists stories newest first`() {
        val folder = folder()
        val first = saved(folder.save(story, date))
        val second = saved(folder.save(story.copy(title = "Bug trace"), date))
        Files.setLastModifiedTime(first.file, FileTime.fromMillis(2_000_000))
        Files.setLastModifiedTime(second.file, FileTime.fromMillis(1_000_000))

        val stories = folder.list()

        assertEquals(listOf(first.id, second.id), stories.map { it.id })
        assertEquals(story, stories.first().story)
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
