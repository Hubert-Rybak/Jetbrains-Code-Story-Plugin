package io.github.hubertrybak.codestory.git

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.commands.Git
import git4idea.commands.GitBinaryHandler
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import io.github.hubertrybak.codestory.StoryGitMetadata
import io.github.hubertrybak.codestory.core.StepChanges
import io.github.hubertrybak.codestory.core.StoryGit
import java.nio.file.Files
import java.nio.file.Path

class GitStoryMetadata : StoryGitMetadata {
    override fun snapshot(project: Project, root: Path): StoryGit {
        if (!Files.exists(root.resolve(".git"))) return StoryGit()
        // Read the story root even when the IDE project is a subfolder or VCS mappings are not ready yet.
        return StoryGit(
            read(project, root, GitCommand.BRANCH.readLockingCommand(), "--show-current"),
            read(project, root, GitCommand.REV_PARSE, "--verify", "HEAD"),
        )
    }

    override fun commit(project: Project, root: Path, revision: String): String? {
        // The revision can come from a shared story file, so Git never gets a value that it could read as an option.
        if (!Files.exists(root.resolve(".git")) || !StepChanges.isRevision(revision)) return null
        return read(project, root, GitCommand.REV_PARSE, "--verify", "--quiet", "$revision^{commit}")?.takeIf { sha.matches(it) }
    }

    override fun fileAt(project: Project, root: Path, commit: String, path: String): ByteArray? {
        if (!sha.matches(commit)) return null
        // The File and Path constructors differ between IDE versions; this one exists in all of them.
        val directory = LocalFileSystem.getInstance().findFileByNioFile(root) ?: return null
        val handler = GitBinaryHandler(project, directory, GitCommand.CAT_FILE)
        handler.addParameters("-p", "$commit:$path")
        handler.setSilent(true)
        return try {
            handler.run()
        } catch (_: VcsException) {
            null
        }
    }

    private fun read(project: Project, root: Path, command: GitCommand, vararg arguments: String): String? {
        val handler = GitLineHandler(project, root.toFile(), command)
        handler.addParameters(*arguments)
        handler.setSilent(true)
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) result.outputAsJoinedString.trim().takeIf { it.isNotEmpty() } else null
    }

    override fun subscribe(project: Project, disposable: Disposable, changed: () -> Unit) {
        project.messageBus.connect(disposable).subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { changed() })
    }

    private companion object {
        /** A full SHA-1 or SHA-256 commit ID. */
        val sha = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
    }
}
