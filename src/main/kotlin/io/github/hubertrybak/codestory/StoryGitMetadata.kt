package io.github.hubertrybak.codestory

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import io.github.hubertrybak.codestory.core.StoryGit
import java.nio.file.Path

/** Git support is loaded through an optional extension, so the rest of the plugin works without Git4Idea. */
interface StoryGitMetadata {
    /** Reads branch and commit at the repository root. Call on a background thread. */
    fun snapshot(project: Project, root: Path): StoryGit

    fun subscribe(project: Project, disposable: Disposable, changed: () -> Unit)

    companion object {
        val EP: ExtensionPointName<StoryGitMetadata> = ExtensionPointName.create("io.github.hubertrybak.codestory.gitMetadata")
    }
}
