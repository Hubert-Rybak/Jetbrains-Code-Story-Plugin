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
     * The full SHA of the commit that [ref] names, or `null` when Git does not know it. [ref] is
     * a validated story base: `HEAD` or a commit SHA. Call on a background thread.
     */
    fun resolveCommit(project: Project, root: Path, ref: String): String?

    /** The content of [file] (relative to the repository root) at [commit], or `null` when the commit has no such file. Call on a background thread. */
    fun fileAt(project: Project, root: Path, commit: String, file: String): ByteArray?

    /**
     * The files that Git sees as renamed between [commit] and the working tree: the path now to
     * the path at [commit]. Compares the whole working tree, so call it once, on a background thread.
     */
    fun renames(project: Project, root: Path, commit: String): Map<String, String>

    companion object {
        val EP: ExtensionPointName<StoryGitMetadata> = ExtensionPointName.create("io.github.hubertrybak.codestory.gitMetadata")
    }
}
