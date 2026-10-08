package io.github.hubertrybak.codestory

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.hubertrybak.codestory.core.LiveRange
import io.github.hubertrybak.codestory.core.Location
import io.github.hubertrybak.codestory.core.StepStatus
import io.github.hubertrybak.codestory.core.StoredStory
import io.github.hubertrybak.codestory.core.StoryAnchors
import io.github.hubertrybak.codestory.core.StoryFiles
import java.io.IOException
import java.nio.file.Path

/** The live markers of the playing story at one moment, with the text of their documents. */
class LiveSnapshot(val storyKey: String, val ranges: Map<String, LiveRange>, val texts: Map<Path, CharSequence>)

/**
 * Live range markers for the story that plays. The markers move with every edit in their
 * documents, so the steps of the playing story follow the code while the developer types.
 * Other stories find their code by anchor text when they are listed or played.
 */
@Service(Service.Level.PROJECT)
class AnchorTracker : Disposable {
    private class Marker(val stepId: String, val file: String, val rangeText: String?, val path: Path, val marker: RangeMarker)

    /** Replaced as a whole on the EDT; background reads see one consistent list. */
    @Volatile
    private var tracked: Pair<String, List<Marker>>? = null

    /**
     * Finds the code of each step of [stored] in [files], using live markers where they exist, and
     * adds markers for steps whose code was found. Call on the EDT.
     */
    fun track(stored: StoredStory, files: StoryFiles): StoredStory {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val previous = tracked?.takeIf { it.first == stored.key }?.second.orEmpty()
        if (tracked?.first != stored.key) release()
        // A marker belongs to the code that its step explained when the marker was made.
        val locations = stored.story.steps.mapNotNull { step -> step.id?.let { id -> step.location?.let { id to it } } }.toMap()
        val (kept, stale) = previous.partition { marker ->
            val location = locations[marker.stepId]
            marker.marker.isValid && location != null && location.file == marker.file && location.rangeText == marker.rangeText
        }
        stale.forEach { it.marker.dispose() }
        val resolution = StoryAnchors.resolve(stored.story, files, ranges(kept))
        val keptIds = kept.map { it.stepId }.toSet()
        val created = resolution.story.steps.withIndex().mapNotNull { (index, step) ->
            val id = step.id ?: return@mapNotNull null
            val location = step.location ?: return@mapNotNull null
            if (id in keptIds || resolution.statuses[index] == StepStatus.LOST) null else marker(stored.root, id, location)
        }
        tracked = stored.key to kept + created
        return stored.resolved(resolution)
    }

    /** The lines that the markers followed and the texts of their documents, read together. */
    fun snapshot(): LiveSnapshot? = ApplicationManager.getApplication().runReadAction(Computable {
        val (storyKey, markers) = tracked ?: return@Computable null
        val valid = markers.filter { it.marker.isValid }
        LiveSnapshot(storyKey, ranges(valid), valid.associate { it.path to it.marker.document.immutableCharSequence })
    })

    /** Removes the markers. Call on the EDT. */
    fun release() {
        val old = tracked
        tracked = null
        old?.second?.forEach { it.marker.dispose() }
    }

    override fun dispose() = release()

    private fun ranges(markers: List<Marker>): Map<String, LiveRange> = markers.filter { it.marker.isValid }.associate { marker ->
        val document = marker.marker.document
        marker.stepId to LiveRange(
            marker.file, marker.rangeText,
            document.getLineNumber(marker.marker.startOffset) + 1, document.getLineNumber(marker.marker.endOffset) + 1,
        )
    }

    /** A marker over the whole lines of [location]. It survives a reload of the file from disk. */
    private fun marker(root: Path, stepId: String, location: Location): Marker? {
        val path = root.resolve(location.file)
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)?.takeUnless { it.isDirectory } ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        if (location.endLine > document.lineCount) return null
        val marker = document.createRangeMarker(document.getLineStartOffset(location.startLine - 1), document.getLineEndOffset(location.endLine - 1), true)
        return Marker(stepId, location.file, location.rangeText, realPath(path), marker)
    }

    companion object {
        fun getInstance(project: Project): AnchorTracker = project.service()

        internal fun realPath(path: Path): Path = try {
            path.toRealPath()
        } catch (_: IOException) {
            path.toAbsolutePath().normalize()
        }
    }
}
