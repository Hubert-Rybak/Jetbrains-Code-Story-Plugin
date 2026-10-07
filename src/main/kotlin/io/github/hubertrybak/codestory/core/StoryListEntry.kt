package io.github.hubertrybak.codestory.core

import kotlinx.serialization.Serializable

/** MCP structured results must be objects, including when the collection is empty. */
@Serializable
data class StoryListReport(
    val stories: List<StoryListEntry>,
    val next: String,
)

/** The agent's index of saved stories. Writing warnings are deliberately absent. */
@Serializable
data class StoryListEntry(
    val id: String,
    val title: String,
    val type: StoryType,
    val date: String,
    val branch: String?,
    val folder: String,
    /** The most serious step status: lost, then code-changed, then moved. `ok` when every step is ok. */
    val status: StepStatus,
    /** Steps whose code the plugin found at other lines. */
    val movedSteps: Int,
    /** Steps whose range has other code than the code that the step explains. */
    val changedSteps: Int,
    /** Steps whose code the plugin cannot find, including steps whose file is gone. */
    val lostSteps: Int,
) {
    companion object {
        fun of(stored: StoredStory): StoryListEntry {
            val counts = stored.story.steps.indices.groupingBy { stored.status(it) }.eachCount()
            val status = listOf(StepStatus.LOST, StepStatus.CODE_CHANGED, StepStatus.MOVED).firstOrNull { it in counts } ?: StepStatus.OK
            return StoryListEntry(
                stored.id, stored.story.title, stored.story.type, stored.date, stored.story.git.branch, stored.folder, status,
                movedSteps = counts[StepStatus.MOVED] ?: 0,
                changedSteps = counts[StepStatus.CODE_CHANGED] ?: 0,
                lostSteps = counts[StepStatus.LOST] ?: 0,
            )
        }
    }
}
