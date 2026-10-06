package io.github.hubertrybak.codestory

import com.intellij.ide.ui.UISettings
import com.intellij.testFramework.FileEditorManagerTestCase
import io.github.hubertrybak.codestory.core.Location
import io.github.hubertrybak.codestory.ui.StepNavigator
import java.nio.file.Path

/** Uses real editor tabs: the ordinary headless test editor manager has no preview tabs. */
class StoryPreviewTabTest : FileEditorManagerTestCase() {
    fun `test step navigation follows the IDE preview tab setting`() {
        val file = createTempFile("story-preview.txt", "first\nsecond\n".toByteArray())
        val root = Path.of(file.path).parent
        val settings = UISettings.getInstance()
        val before = settings.openInPreviewTabIfPossible
        val navigator = StepNavigator.getInstance(project)
        val tabs = checkNotNull(manager)
        try {
            settings.openInPreviewTabIfPossible = true
            navigator.open(root, Location(file.name, 2, 2))
            assertTrue(tabs.getComposite(file)!!.isPreview)
            navigator.clear()
            tabs.closeFile(file)
            settings.openInPreviewTabIfPossible = false
            navigator.open(root, Location(file.name, 2, 2))
            assertFalse(tabs.getComposite(file)!!.isPreview)
        } finally {
            navigator.clear()
            settings.openInPreviewTabIfPossible = before
        }
    }
}
