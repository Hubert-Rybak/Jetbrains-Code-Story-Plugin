package io.github.hubertrybak.codestory

import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A real local Git repository; no remotes or user Git configuration are needed. */
class GitFixture(private val root: Path) {
    fun init(commit: Boolean = true) {
        command("init", "--initial-branch=story-branch")
        if (commit) commit("src") else refresh()
    }

    /** Commits [paths] and returns the new commit SHA. */
    fun commit(vararg paths: String): String {
        command("add", *paths)
        command("-c", "user.name=Code Story Test", "-c", "user.email=story@example.test", "commit", "--no-gpg-sign", "-m", "Test code")
        refresh()
        return command("rev-parse", "HEAD")
    }

    /** The native commands write outside VFS. Refresh on the EDT before a tool call runs in the background. */
    fun refresh() {
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)).refresh(false, true)
    }

    fun command(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments).directory(root.toFile()).redirectErrorStream(true).start()
        check(process.waitFor(20, TimeUnit.SECONDS)) { "Git fixture timed out" }
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        check(process.exitValue() == 0) { "Git fixture failed: $output" }
        return output
    }
}
