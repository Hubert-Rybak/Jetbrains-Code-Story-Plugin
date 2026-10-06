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
    val status: String,
    /** Code locations that cannot currently be read, including ranges outside their files. */
    val lostSteps: Int,
)
