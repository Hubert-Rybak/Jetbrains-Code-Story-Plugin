package io.github.hubertrybak.codestory.git

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import io.github.hubertrybak.codestory.StoryGitMetadata
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
}
