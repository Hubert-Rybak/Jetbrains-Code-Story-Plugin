package io.github.hubertrybak.codestory

import com.intellij.util.xmlb.XmlSerializer
import io.github.hubertrybak.codestory.core.MessageAuthor
import io.github.hubertrybak.codestory.core.ReplyOutcome
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.Story
import io.github.hubertrybak.codestory.core.StoryAuthor
import io.github.hubertrybak.codestory.core.StoryFolder
import io.github.hubertrybak.codestory.core.StoryThread
import io.github.hubertrybak.codestory.core.StoryType
import io.github.hubertrybak.codestory.core.ThreadKind
import io.github.hubertrybak.codestory.core.ThreadMessage
import io.github.hubertrybak.codestory.core.ThreadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class StoryReaderStateTest {
    private val root = Path.of("/repo")
    private val local = stored(StoryFolder.LOCAL_DIR)
    private val shared = stored(StoryFolder.SHARED_DIR)

    @Test
    fun `legacy workspace state moves to the unique file and survives serialization`() {
        val legacyDraft = StoryDraft(story = shared.legacyKey, step = "entry", text = "Why here?")
        val reader = StoryReaderState().apply {
            loadState(StoryReaderState.ReadStories().apply {
                opened.add(shared.legacyKey)
                drafts.add(legacyDraft)
                readAnswers["${shared.legacyKey}/answer"] = 1
            })
        }

        assertTrue(reader.prepare(shared, legacyIdentityUnique = true))
        assertFalse(reader.prepare(shared, legacyIdentityUnique = true))
        val restored = StoryReaderState().apply {
            loadState(XmlSerializer.deserialize(XmlSerializer.serialize(reader.state), StoryReaderState.ReadStories::class.java))
        }
        assertFalse(restored.isNew(shared))
        assertEquals(listOf(legacyDraft.copy(story = shared.key)), restored.drafts(shared.key))
        assertFalse(restored.hasUnread(shared, listOf(answer(1))))
        assertTrue(restored.hasUnread(shared, listOf(answer(2))))
        assertFalse(restored.state.opened.contains(shared.legacyKey))
        assertTrue(restored.drafts(shared.legacyKey).isEmpty())
        assertFalse(restored.state.readAnswers.containsKey("${shared.legacyKey}/answer"))
    }

    @Test
    fun `ambiguous legacy state is not assigned to local or shared copies`() {
        val legacyDraft = StoryDraft(story = local.legacyKey, text = "An older draft")
        val reader = StoryReaderState().apply {
            loadState(StoryReaderState.ReadStories().apply {
                opened.add(local.legacyKey)
                drafts.add(legacyDraft)
                readAnswers["${local.legacyKey}/answer"] = 1
            })
        }

        assertFalse(reader.prepare(local, legacyIdentityUnique = false))
        assertFalse(reader.prepare(shared, legacyIdentityUnique = false))
        assertTrue(reader.isNew(shared))
        assertTrue(reader.drafts(local.key).isEmpty())
        assertTrue(reader.drafts(shared.key).isEmpty())
        assertTrue(reader.hasUnread(local, listOf(answer(1))))
        assertTrue(reader.hasUnread(shared, listOf(answer(1))))
        assertEquals(listOf(legacyDraft), reader.drafts(local.legacyKey))
    }

    @Test
    fun `copies with the same ID have independent drafts and unread state`() {
        val reader = StoryReaderState()
        val localDraft = StoryDraft(story = local.key, text = "Local question")
        val sharedDraft = StoryDraft(story = shared.key, text = "Shared question")
        reader.saveDraft(localDraft)
        reader.saveDraft(sharedDraft)
        reader.opened(local)
        reader.markRead(local, listOf(answer(1)))

        assertEquals(listOf(localDraft), reader.drafts(local.key))
        assertEquals(listOf(sharedDraft), reader.drafts(shared.key))
        assertTrue(reader.isNew(shared))
        assertFalse(reader.hasUnread(local, listOf(answer(1))))
        assertTrue(reader.hasUnread(shared, listOf(answer(1))))

        reader.deleteStoryDrafts(local.key)
        assertTrue(reader.drafts(local.key).isEmpty())
        assertEquals(listOf(sharedDraft), reader.drafts(shared.key))
        reader.markRead(shared, listOf(answer(1)))
        assertFalse(reader.hasUnread(shared, listOf(answer(1))))
        assertTrue(reader.hasUnread(local, listOf(answer(1))))
    }

    @Test
    fun `sharing and unsharing retain reader state through the round trip`() {
        val reader = StoryReaderState()
        val draft = StoryDraft(story = local.key, step = "entry", thread = "answer", text = "One more thing")
        reader.saveDraft(draft)
        reader.opened(local)
        reader.markRead(local, listOf(answer(1)))

        assertTrue(reader.relocated(local, shared))
        assertFalse(reader.isNew(shared))
        assertEquals(listOf(draft.copy(story = shared.key)), reader.drafts(shared.key))
        assertFalse(reader.hasUnread(shared, listOf(answer(1))))
        assertTrue(reader.drafts(local.key).isEmpty())
        assertFalse(reader.state.opened.contains(local.key))
        assertFalse(reader.state.readAnswers.containsKey("${local.key}/answer"))

        reader.markRead(shared, listOf(answer(2)))
        assertTrue(reader.relocated(shared, local))
        assertEquals(listOf(draft), reader.drafts(local.key))
        assertFalse(reader.hasUnread(local, listOf(answer(2))))
        assertTrue(reader.drafts(shared.key).isEmpty())
        assertFalse(reader.relocated(local, local))
        assertFalse(reader.relocated(shared, local))
    }

    @Test
    fun `migration preserves newer reader state already saved under file identity`() {
        val reader = StoryReaderState().apply {
            loadState(StoryReaderState.ReadStories().apply {
                readAnswers["${shared.legacyKey}/answer"] = 1
                readAnswers["${shared.key}/answer"] = 2
            })
        }

        assertTrue(reader.prepare(shared, legacyIdentityUnique = true))
        assertFalse(reader.hasUnread(shared, listOf(answer(2))))
        assertTrue(reader.hasUnread(shared, listOf(answer(3))))
        assertEquals(mapOf("${shared.key}/answer" to 2), reader.state.readAnswers)
    }

    @Test
    fun `legacy migration requires a known unique file and ambiguity clears that decision`() {
        val reader = StoryReaderState()
        assertFalse(reader.canMigrateLegacy(local))
        assertFalse(reader.canMigrateLegacy(shared))

        reader.prepare(local, legacyIdentityUnique = true)
        assertTrue(reader.canMigrateLegacy(local))
        assertFalse(reader.canMigrateLegacy(shared))

        reader.prepare(shared, legacyIdentityUnique = false)
        assertFalse(reader.canMigrateLegacy(local))
        assertFalse(reader.canMigrateLegacy(shared))
    }

    @Test
    fun `legacy uniqueness follows relocation but must be checked again after loading workspace state`() {
        val reader = StoryReaderState()
        reader.prepare(local, legacyIdentityUnique = true)
        reader.relocated(local, shared)
        assertFalse(reader.canMigrateLegacy(local))
        assertTrue(reader.canMigrateLegacy(shared))

        reader.relocated(shared, local)
        assertTrue(reader.canMigrateLegacy(local))
        assertFalse(reader.canMigrateLegacy(shared))

        reader.loadState(reader.state)
        assertFalse(reader.canMigrateLegacy(local))
        assertFalse(reader.canMigrateLegacy(shared))
    }

    private fun stored(folder: String): StoredStory = StoredStory(
        "same-id", root, root.resolve(".codestory/$folder/same-id.story.json"),
        Story("Story", StoryType.EXPLORATION, "Summary", "Request", StoryAuthor("Agent", "Model"), emptyList()),
        FileTime.fromMillis(0),
    )

    private fun answer(round: Int) = StoryThread(
        "answer", ThreadKind.QUESTION, ThreadState.ANSWERED,
        listOf(ThreadMessage(MessageAuthor.AGENT, round, Instant.EPOCH, "Because.", ReplyOutcome.ANSWERED)),
    )
}
