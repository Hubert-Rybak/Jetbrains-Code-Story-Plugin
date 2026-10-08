package io.github.hubertrybak.codestory.git

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.util.GitFileUtils
import io.github.hubertrybak.codestory.StoryGitMetadata
import io.github.hubertrybak.codestory.core.StoryGit
import java.nio.file.Files
import java.nio.file.Path

class GitStoryMetadata : StoryGitMetadata {
    override fun snapshot(project: Project, root: Path): StoryGit {
        if (!isRepository(root)) return StoryGit()
        // Read the story root even when the IDE project is a subfolder or VCS mappings are not ready yet.
        return StoryGit(
            read(project, root, GitCommand.BRANCH.readLockingCommand(), "--show-current"),
            read(project, root, GitCommand.REV_PARSE, "--verify", "HEAD"),
        )
    }

    override fun resolveCommit(project: Project, root: Path, ref: String): String? {
        if (!isRepository(root)) return null
        return read(project, root, GitCommand.REV_PARSE, "--verify", "--quiet", "$ref^{commit}")
    }

    override fun fileAt(project: Project, root: Path, commit: String, file: String): ByteArray? {
        val directory = LocalFileSystem.getInstance().let { it.findFileByNioFile(root) ?: it.refreshAndFindFileByNioFile(root) } ?: return null
        return try {
            GitFileUtils.getFileContent(project, directory, commit, file)
        } catch (_: VcsException) {
            null
        }
    }

    override fun renames(project: Project, root: Path, commit: String): Map<String, String> {
        if (!isRepository(root)) return emptyMap()
        val output = read(project, root, GitCommand.DIFF, "--name-status", "-z", "--no-color", "--find-renames", "--diff-filter=R", "--no-ext-diff", commit, "--")
            ?: return emptyMap()
        // With -z, each rename is "R<score>", the old path and the new path, separated by NUL characters.
        return output.split('\u0000').chunked(3).filter { it.size == 3 && it[0].startsWith("R") }.associate { it[2] to it[1] }
    }

    private fun read(project: Project, root: Path, command: GitCommand, vararg arguments: String): String? {
        val handler = GitLineHandler(project, root.toFile(), command)
        handler.addParameters(*arguments)
        handler.setSilent(true)
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) result.outputAsJoinedString.trim().takeIf { it.isNotEmpty() } else null
    }

    private fun isRepository(root: Path) = Files.exists(root.resolve(".git"))

    override fun subscribe(project: Project, disposable: Disposable, changed: () -> Unit) {
        project.messageBus.connect(disposable).subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { changed() })
    }
}
