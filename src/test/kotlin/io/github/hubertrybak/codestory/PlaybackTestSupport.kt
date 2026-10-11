package io.github.hubertrybak.codestory

import com.intellij.testFramework.PlatformTestUtil
import io.github.hubertrybak.codestory.ui.StoryPlayer

/** Playback deliberately yields the EDT while it reads and resolves the story's source files. */
internal fun StoryPlayer.awaitPlayback() {
    PlatformTestUtil.waitWithEventsDispatching("the story's anchors did not finish resolving", { !isResolving }, 20)
}
