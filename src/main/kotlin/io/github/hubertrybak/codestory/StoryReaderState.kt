package io.github.hubertrybak.codestory

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import io.github.hubertrybak.codestory.core.DraftComment
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryFolder
import io.github.hubertrybak.codestory.core.ThreadKind
import java.util.UUID

/**
 * A comment that the reader did not submit, for the story with [story] key: on the story, on the
 * step [step], or a reply in the thread [thread]. Mutable only for workspace serialization.
 */
data class StoryDraft(
    var id: String = UUID.randomUUID().toString(),
    var story: String = "",
    var step: String? = null,
    var thread: String? = null,
    var kind: ThreadKind = ThreadKind.QUESTION,
    var text: String = "",
) {
    fun comment() = DraftComment(text, step, thread, kind)
}

/** Shared arrivals remain new until opened. Drafts wait here until Submit. Reader state never changes the shared file. */
@Service(Service.Level.PROJECT)
@State(name = "CodeStoryReaderState", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class StoryReaderState : PersistentStateComponent<StoryReaderState.ReadStories> {
    class ReadStories {
        var opened: MutableSet<String> = mutableSetOf()
        var drafts: MutableList<StoryDraft> = mutableListOf()
    }

    private var data = ReadStories()
    @Synchronized override fun getState() = ReadStories().also {
        it.opened.addAll(data.opened)
        it.drafts.addAll(data.drafts.map(StoryDraft::copy))
    }
    @Synchronized override fun loadState(state: ReadStories) { data = state }
    @Synchronized fun isNew(stored: StoredStory) = stored.folder == StoryFolder.SHARED_DIR && stored.key !in data.opened
    @Synchronized fun opened(stored: StoredStory): Boolean = data.opened.add(stored.key)

    /** The drafts of the story with [storyKey], in the order the reader wrote them. */
    @Synchronized fun drafts(storyKey: String): List<StoryDraft> = data.drafts.filter { it.story == storyKey }.map(StoryDraft::copy)

    /** Adds the draft, or replaces the draft with the same ID. */
    @Synchronized fun saveDraft(draft: StoryDraft) {
        val index = data.drafts.indexOfFirst { it.id == draft.id }
        if (index < 0) data.drafts.add(draft.copy()) else data.drafts[index] = draft.copy()
    }

    @Synchronized fun deleteDrafts(ids: Collection<String>) { data.drafts.removeAll { it.id in ids } }

    @Synchronized fun deleteStoryDrafts(storyKey: String) { data.drafts.removeAll { it.story == storyKey } }
}
