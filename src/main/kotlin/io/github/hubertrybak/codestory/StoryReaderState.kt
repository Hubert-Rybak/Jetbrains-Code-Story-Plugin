package io.github.hubertrybak.codestory

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryFolder

/** Shared arrivals remain new until opened. Reader state never changes the shared file. */
@Service(Service.Level.PROJECT)
@State(name = "CodeStoryReaderState", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class StoryReaderState : PersistentStateComponent<StoryReaderState.ReadStories> {
    class ReadStories {
        var opened: MutableSet<String> = mutableSetOf()
    }

    private var data = ReadStories()
    @Synchronized override fun getState() = ReadStories().also { it.opened.addAll(data.opened) }
    @Synchronized override fun loadState(state: ReadStories) { data = state }
    @Synchronized fun isNew(stored: StoredStory) = stored.folder == StoryFolder.SHARED_DIR && stored.key !in data.opened
    @Synchronized fun opened(stored: StoredStory): Boolean = data.opened.add(stored.key)
}
