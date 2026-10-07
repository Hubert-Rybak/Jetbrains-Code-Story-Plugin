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

    /**
     * The full SHA of the commit that [revision] names in the repository at [root], or `null` when
     * Git does not know it. Call on a background thread.
     */
    fun commit(project: Project, root: Path, revision: String): String?

    /**
     * The content of [path], relative to [root], at [commit]: a full SHA from [commit]. `null` when
     * the file is not in that commit. Call on a background thread.
     */
    fun fileAt(project: Project, root: Path, commit: String, path: String): ByteArray?

    companion object {
        val EP: ExtensionPointName<StoryGitMetadata> = ExtensionPointName.create("io.github.hubertrybak.codestory.gitMetadata")
    }
}
