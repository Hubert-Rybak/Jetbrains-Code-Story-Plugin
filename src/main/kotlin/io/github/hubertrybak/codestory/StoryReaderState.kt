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
import io.github.hubertrybak.codestory.core.MessageAuthor
import io.github.hubertrybak.codestory.core.StoryThread
import com.intellij.util.messages.Topic
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

fun interface StoryReaderListener {
    fun readerChanged()
    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic(StoryReaderListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/** Shared arrivals remain new until opened. Drafts wait here until Submit. Reader state never changes the shared file. */
@Service(Service.Level.PROJECT)
@State(name = "CodeStoryReaderState", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class StoryReaderState : PersistentStateComponent<StoryReaderState.ReadStories> {
    class ReadStories {
        var opened: MutableSet<String> = mutableSetOf()
        var drafts: MutableList<StoryDraft> = mutableListOf()
        var readAnswers: MutableMap<String, Int> = mutableMapOf()
    }

    private var data = ReadStories()
    @Synchronized override fun getState() = ReadStories().also {
        it.opened.addAll(data.opened)
        it.drafts.addAll(data.drafts.map(StoryDraft::copy))
        it.readAnswers.putAll(data.readAnswers)
    }
    @Synchronized override fun loadState(state: ReadStories) { data = state }
    @Synchronized fun isNew(stored: StoredStory) = stored.folder == StoryFolder.SHARED_DIR && stored.key !in data.opened
    @Synchronized fun opened(stored: StoredStory): Boolean = data.opened.add(stored.key)

    @Synchronized fun hasUnread(stored: StoredStory, threads: List<StoryThread>): Boolean = threads.any { thread ->
        latestAnswer(thread) > (data.readAnswers[answerKey(stored, thread)] ?: 0)
    }

    /** Acknowledges only answers that the UI actually displayed. New rounds remain unread. */
    @Synchronized fun markRead(stored: StoredStory, threads: List<StoryThread>): Boolean {
        var changed = false
        threads.forEach { thread ->
            val key = answerKey(stored, thread)
            val round = latestAnswer(thread)
            if (round > (data.readAnswers[key] ?: 0)) {
                data.readAnswers[key] = round
                changed = true
            }
        }
        return changed
    }

    private fun answerKey(stored: StoredStory, thread: StoryThread) = stored.key + "/" + thread.id
    private fun latestAnswer(thread: StoryThread) = thread.messages.lastOrNull { it.author == MessageAuthor.AGENT }?.round ?: 0

    /** The drafts of the story with [storyKey], in the order the reader wrote them. */
    @Synchronized fun drafts(storyKey: String): List<StoryDraft> = data.drafts.filter { it.story == storyKey }.map(StoryDraft::copy)

    /** Adds the draft, or replaces the draft with the same ID. */
    @Synchronized fun saveDraft(draft: StoryDraft) {
        val index = data.drafts.indexOfFirst { it.id == draft.id }
        if (index < 0) data.drafts.add(draft.copy()) else data.drafts[index] = draft.copy()
    }

    @Synchronized fun deleteDrafts(ids: Collection<String>) { data.drafts.removeAll { it.id in ids } }

    @Synchronized fun deleteStoryDrafts(storyKey: String) {
        data.drafts.removeAll { it.story == storyKey }
        data.readAnswers.keys.removeAll { it.startsWith("$storyKey/") }
    }
}
