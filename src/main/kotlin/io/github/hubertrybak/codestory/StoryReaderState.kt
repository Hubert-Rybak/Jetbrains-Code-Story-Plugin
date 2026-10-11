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
    /** A background inventory authorizes legacy migration only for the unique file of an ID. */
    private val uniqueLegacyFiles = mutableMapOf<String, String>()
    @Synchronized override fun getState() = ReadStories().also {
        it.opened.addAll(data.opened)
        it.drafts.addAll(data.drafts.map(StoryDraft::copy))
        it.readAnswers.putAll(data.readAnswers)
    }
    @Synchronized override fun loadState(state: ReadStories) {
        data = state
        uniqueLegacyFiles.clear()
    }

    /**
     * Migrates workspace state written before reader identities included the story file.
     * The caller checks uniqueness against the available files, away from the reader UI.
     * Ambiguous legacy state stays unassigned rather than appearing on either copy.
     */
    @Synchronized fun prepare(stored: StoredStory, legacyIdentityUnique: Boolean): Boolean {
        if (legacyIdentityUnique) uniqueLegacyFiles[stored.legacyKey] = stored.key
        else uniqueLegacyFiles.remove(stored.legacyKey)
        return legacyIdentityUnique && moveState(stored.legacyKey, stored.key)
    }

    /** Other workspace state may migrate on the EDT only after an unambiguous background lookup. */
    @Synchronized fun canMigrateLegacy(stored: StoredStory): Boolean = uniqueLegacyFiles[stored.legacyKey] == stored.key

    /** Sharing and unsharing move the same story, including its workspace-only reader state. */
    @Synchronized fun relocated(previous: StoredStory, moved: StoredStory): Boolean {
        if (uniqueLegacyFiles[previous.legacyKey] == previous.key) {
            uniqueLegacyFiles.remove(previous.legacyKey)
            uniqueLegacyFiles[moved.legacyKey] = moved.key
        }
        return moveState(previous.key, moved.key)
    }

    private fun moveState(previousKey: String, currentKey: String): Boolean {
        if (previousKey == currentKey) return false
        var changed = false
        if (data.opened.remove(previousKey)) {
            data.opened.add(currentKey)
            changed = true
        }
        data.drafts.indices.forEach { index ->
            val draft = data.drafts[index]
            if (draft.story == previousKey) {
                data.drafts[index] = draft.copy(story = currentKey)
                changed = true
            }
        }
        val prefix = "$previousKey/"
        val answers = data.readAnswers.filterKeys { it.startsWith(prefix) }
        answers.forEach { (key, round) ->
            data.readAnswers.remove(key)
            val target = "$currentKey/" + key.removePrefix(prefix)
            data.readAnswers[target] = maxOf(round, data.readAnswers[target] ?: 0)
            changed = true
        }
        return changed
    }

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
