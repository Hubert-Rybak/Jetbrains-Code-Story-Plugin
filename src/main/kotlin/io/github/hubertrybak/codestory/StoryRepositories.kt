package io.github.hubertrybak.codestory

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import io.github.hubertrybak.codestory.core.ErrorCode
import io.github.hubertrybak.codestory.core.ReportItem
import io.github.hubertrybak.codestory.core.Story
import io.github.hubertrybak.codestory.core.StoryFolder
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Project VCS mappings include sibling repositories and submodules, without scanning the whole disk. */
class StoryRepositories(private val project: Project) {
    fun folders(): List<StoryFolder> {
        val fallback = StoryFolder.forProject(Path.of(checkNotNull(project.basePath)))
        val mapped = ApplicationManager.getApplication().runReadAction(Computable {
            ProjectLevelVcsManager.getInstance(project).getAllVersionedRoots().map { it.toNioPath() }
        })
        val roots = (mapped + fallback.root).filter { Files.exists(it.resolve(".git")) }
            .map { it.toAbsolutePath().normalize() }.distinct().sortedBy { it.toString() }
        return if (roots.isEmpty()) listOf(fallback) else roots.map(::StoryFolder)
    }

    class Selection(val folder: StoryFolder? = null, val error: ReportItem? = null)

    fun select(story: Story, id: String?, repositoryPath: String?): Selection {
        val folders = folders()
        val requested = repositoryPath?.let { path ->
            val root = try { Path.of(path).toAbsolutePath().normalize() } catch (_: InvalidPathException) { null }
            folders.singleOrNull { it.root == root }
                ?: return failure(ErrorCode.INVALID_REPOSITORY, "The repository is not part of this project.")
        }
        if (id != null) {
            val matches = (requested?.let(::listOf) ?: folders).filter { it.get(id) != null }
            if (matches.isEmpty()) return failure(ErrorCode.STORY_NOT_FOUND, "The story `$id` was not found.")
            if (matches.size > 1) return failure(ErrorCode.REPOSITORY_REQUIRED, "That story ID exists in several repositories.")
            return checkBoundary(matches.single(), story, folders)
        }
        if (requested != null) return checkBoundary(requested, story, folders)
        if (folders.size == 1) return checkBoundary(folders.single(), story, folders)

        val paths = story.steps.mapNotNull { it.location?.file }
        val candidates = folders.filter { folder -> paths.isNotEmpty() && paths.all { Files.isRegularFile(folder.root.resolve(it)) } }
        if (candidates.size == 1) return checkBoundary(candidates.single(), story, folders)
        if (candidates.size > 1 || paths.isEmpty()) return failure(ErrorCode.REPOSITORY_REQUIRED, "Choose the repository for this story.")
        // Diagnose mixed repository-relative paths, and project-relative paths that name two roots.
        val owners = paths.flatMap { path ->
            folders.filter { Files.isRegularFile(it.root.resolve(path)) }.map { it.root } +
                listOfNotNull(owner(Path.of(project.basePath!!).resolve(path), folders))
        }.distinct()
        return if (owners.size > 1) failure(ErrorCode.CROSS_REPOSITORY, "A story cannot contain steps from several repositories.")
        else if (owners.size == 1) folders.singleOrNull { it.root == owners.single() }?.let { checkBoundary(it, story, folders) }
            ?: failure(ErrorCode.INVALID_REPOSITORY, "The files belong to a repository that is not mapped in this project.")
        else failure(ErrorCode.REPOSITORY_REQUIRED, "The files do not identify a repository in this project.")
    }

    /** A nested Git root or a symlink into another repository must not slip through validation. */
    private fun checkBoundary(folder: StoryFolder, story: Story, folders: List<StoryFolder>): Selection {
        for ((index, step) in story.steps.withIndex()) {
            val location = step.location ?: continue
            val path = folder.root.resolve(location.file)
            val owner = owner(path, folders)
            if (owner != null && owner != folder.root) return Selection(error = ReportItem(
                index + 1, ErrorCode.CROSS_REPOSITORY, "Step ${index + 1} belongs to another repository.",
                "Split the story so that every step belongs to the selected repository.",
            ))
        }
        return Selection(folder)
    }

    private fun owner(path: Path, folders: List<StoryFolder>): Path? {
        if (!Files.exists(path)) return null
        val real = path.toRealPath()
        val gitRoot = StoryFolder.findRoot(real.parent)
        return if (Files.exists(gitRoot.resolve(".git"))) gitRoot
        else folders.map { it.root }.filter { real.startsWith(it) }.maxByOrNull { it.nameCount }
    }

    private fun failure(code: ErrorCode, message: String) = Selection(error = ReportItem(
        null, code, message,
        if (code == ErrorCode.CROSS_REPOSITORY) "Split the story into one story per repository."
        else "Pass repositoryPath with the absolute repository root; use code_story_list to find an existing story.",
    ))
}
