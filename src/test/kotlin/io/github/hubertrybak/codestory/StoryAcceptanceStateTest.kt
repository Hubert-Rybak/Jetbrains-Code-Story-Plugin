package io.github.hubertrybak.codestory

import io.github.hubertrybak.codestory.core.Location
import io.github.hubertrybak.codestory.core.Step
import io.github.hubertrybak.codestory.core.Story
import io.github.hubertrybak.codestory.core.StoryAuthor
import io.github.hubertrybak.codestory.core.StoryType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

class StoryAcceptanceStateTest {
    private val file = Path.of("repo/.codestory/local/2026-10-08-story.story.json")
    private val original = Story(
        "Story", StoryType.EXPLORATION, "Summary", "Request", StoryAuthor("Agent", "Model"),
        listOf(Step("Entry", "Starts here.", Location("App.kt", 2, 2, "code", "code"), "entry")),
        createdAt = Instant.EPOCH,
    )

    @Test
    fun `a delayed watcher cannot overwrite a newer accepted anchor update`() {
        val state = StoryAcceptanceState().apply { remember(file, original) }
        val moved = atLine(3)
        state.anchorsUpdated(file, original, moved)
        // The watcher captured original before the writer saved and acknowledged moved.
        state.anchorsUpdated(file, original, atLine(4))

        assertTrue(state.contains(file, moved))
        assertFalse(state.contains(file, original))
        assertFalse(state.contains(file, atLine(4)))
    }

    @Test
    fun `a delayed anchor update cannot undo acceptance of an author revision`() {
        val state = StoryAcceptanceState().apply { remember(file, original) }
        val revision = original.copy(title = "Revised story")
        state.remember(file, revision)
        state.anchorsUpdated(file, original, atLine(3))

        assertTrue(state.contains(file, revision))
        assertFalse(state.contains(file, atLine(3)))
    }

    private fun atLine(line: Int): Story = original.copy(steps = original.steps.map { step ->
        step.copy(location = step.location!!.copy(startLine = line, endLine = line))
    })
}
