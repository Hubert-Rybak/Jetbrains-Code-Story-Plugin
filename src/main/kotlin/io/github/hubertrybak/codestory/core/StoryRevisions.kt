package io.github.hubertrybak.codestory.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import java.security.MessageDigest

/** Small plugin-owned snapshots keep feedback changes across IDE restarts without copying story text. */
@Serializable
data class StoryStepSnapshot(val id: String, val fingerprint: String)

object StoryRevisions {
    fun snapshot(story: Story): List<StoryStepSnapshot> = story.steps.map { step ->
        val content = buildJsonArray {
            add(step.title); add(step.comment)
            step.location?.let { location ->
                add(location.file); add(location.startLine); add(location.endLine); add(location.anchorText)
            }
        }.toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        StoryStepSnapshot(checkNotNull(step.id), digest.joinToString("") { "%02x".format(it) })
    }

    /** Called only for author revisions, never for automatic anchor corrections or reader actions. */
    fun record(previous: Story, revised: Story, reviewedIds: Set<String> = revised.steps.mapNotNull { it.id }.toSet(), confirmedIds: Set<String> = emptySet()): Story {
        val before = snapshot(previous)
        val after = snapshot(revised)
        fun record(thread: StoryThread): StoryThread {
            if (thread.state != ThreadState.RECEIVED) return thread
            val baseline = thread.storyBaseline ?: before
            val kept = after.map { it.id }.toSet()
            val confirmed = ((thread.confirmedSteps - reviewedIds) + confirmedIds).filter { it in kept }.distinct()
            return thread.copy(storyBaseline = baseline, pendingStoryChanges = summary(baseline, after, confirmed.toSet()),
                stepsToSync = thread.stepsToSync.filter { it in kept && it !in reviewedIds }, confirmedSteps = confirmed)
        }
        return revised.copy(
            threads = revised.threads.map(::record),
            steps = revised.steps.map { it.copy(threads = it.threads.map(::record)) },
        )
    }

    /** Updated/added numbers refer to the revised story; removed numbers refer to the original. */
    fun summary(before: List<StoryStepSnapshot>, after: List<StoryStepSnapshot>, confirmedIds: Set<String> = emptySet()): String? {
        val old = before.associateBy { it.id }
        val current = after.associateBy { it.id }
        val updated = after.mapIndexedNotNull { index, step -> (index + 1).takeIf { step.id !in confirmedIds && old[step.id]?.let { it.fingerprint != step.fingerprint } == true } }
        val added = after.mapIndexedNotNull { index, step -> (index + 1).takeIf { step.id !in old } }
        val removed = before.mapIndexedNotNull { index, step -> (index + 1).takeIf { step.id !in current } }
        val confirmed = after.mapIndexedNotNull { index, step -> (index + 1).takeIf { step.id in confirmedIds && it !in updated && it !in added } }
        fun clause(numbers: List<Int>, verb: String): String? {
            if (numbers.isEmpty()) return null
            val list = if (numbers.size < 2) numbers.single().toString()
                else numbers.dropLast(1).joinToString(", ") + " and " + numbers.last()
            return "${if (numbers.size == 1) "step" else "steps"} $list $verb"
        }
        val clauses = listOfNotNull(clause(updated, "updated"), clause(added, "added"), clause(removed, "removed"), clause(confirmed, "confirmed"))
        return clauses.takeIf { it.isNotEmpty() }?.joinToString(", ")?.replaceFirstChar { it.uppercase() }
    }
}
