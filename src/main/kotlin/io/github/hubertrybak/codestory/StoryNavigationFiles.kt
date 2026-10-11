package io.github.hubertrybak.codestory

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.hubertrybak.codestory.core.StoryPaths
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Rechecks containment when opening saved locations, whose files may have changed since validation. */
internal object StoryNavigationFiles {
    fun find(root: Path, path: String): VirtualFile? {
        val relative = (StoryPaths.normalize(path) as? StoryPaths.Result.Valid)?.path ?: return null
        val target = try {
            val realRoot = root.toRealPath()
            root.resolve(relative).toRealPath().takeIf { it.startsWith(realRoot) && Files.isRegularFile(it) }
        } catch (_: IOException) {
            null
        } catch (_: InvalidPathException) {
            null
        } ?: return null
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)?.takeUnless { it.isDirectory }
    }
}
