package io.github.hubertrybak.codestory

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import io.github.hubertrybak.codestory.core.Story
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryFormat
import io.github.hubertrybak.codestory.core.StoryThreads
import java.nio.file.Path
import java.security.MessageDigest

/** Accepted file drops survive project restarts without storing source text in workspace state. */
@Service(Service.Level.PROJECT)
@State(name = "CodeStoryAcceptanceState", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class StoryAcceptanceState : PersistentStateComponent<StoryAcceptanceState.AcceptedStories> {
    class AcceptedStories {
        var fingerprints: MutableMap<String, String> = mutableMapOf()
    }

    private var data = AcceptedStories()

    @Synchronized override fun getState() = AcceptedStories().also { it.fingerprints.putAll(data.fingerprints) }
    @Synchronized override fun loadState(state: AcceptedStories) {
        data = AcceptedStories().also { it.fingerprints.putAll(state.fingerprints) }
    }

    @Synchronized fun contains(file: Path, story: Story): Boolean = data.fingerprints[file.toString()] == fingerprint(story)

    fun remember(stored: StoredStory) = remember(stored.file, stored.story)

    @Synchronized fun remember(file: Path, story: Story) {
        data.fingerprints[file.toString()] = fingerprint(story)
    }

    /** A delayed anchor writer cannot replace the fingerprint of a newer author revision. */
    fun anchorsUpdated(before: StoredStory, after: StoredStory) = anchorsUpdated(before.file, before.story, after.story)

    /** A watcher may finish reading an older update after another writer already advanced it. */
    @Synchronized fun anchorsUpdated(file: Path, expected: Story, updated: Story) {
        if (contains(file, expected)) remember(file, updated)
    }

    @Synchronized fun forget(file: Path) { data.fingerprints.remove(file.toString()) }

    private fun fingerprint(story: Story): String {
        // Threads may change in another IDE without a delivery. Locations and saved code must match:
        // an incoming rangeText field alone is never evidence that this IDE accepted the story.
        val text = StoryFormat.encode(StoryThreads.strip(story), "accepted-story")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
