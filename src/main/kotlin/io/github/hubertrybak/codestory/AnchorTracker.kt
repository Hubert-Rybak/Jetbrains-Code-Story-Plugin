package io.github.hubertrybak.codestory

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import io.github.hubertrybak.codestory.core.LiveRange
import io.github.hubertrybak.codestory.core.Location
import io.github.hubertrybak.codestory.core.StepStatus
import io.github.hubertrybak.codestory.core.StoredStory
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
    internal class SourceDocument(val path: Path, val document: Document)

    /** Replaced as a whole on the EDT; background reads see one consistent list. */
    @Volatile
    private var tracked: Pair<String, List<Marker>>? = null

    /** Loads every found source document off the EDT, so unopened files also get live markers. */
    internal fun prepare(stored: StoredStory): Map<String, SourceDocument> {
        val application = ApplicationManager.getApplication()
        check(!application.isDispatchThread)
        val files = stored.story.steps.withIndex().mapNotNull { (index, step) ->
            step.location?.file?.takeUnless { stored.status(index) == StepStatus.LOST }
        }.distinct()
        return files.mapNotNull { path ->
            // Recheck canonical containment before reading a file that may have changed since resolution.
            val file = StoryNavigationFiles.find(stored.root, path) ?: return@mapNotNull null
            val document = application.runReadAction(Computable { FileDocumentManager.getInstance().getDocument(file) })
                ?: return@mapNotNull null
            path to SourceDocument(Path.of(file.path), document)
        }.toMap()
    }

    /** Installs an already resolved story. File reads and anchor searches happen in the background. */
    @Synchronized
    internal fun install(stored: StoredStory, documents: Map<String, SourceDocument>) {
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
        val keptIds = kept.map { it.stepId }.toSet()
        val created = stored.story.steps.withIndex().mapNotNull { (index, step) ->
            val id = step.id ?: return@mapNotNull null
            val location = step.location ?: return@mapNotNull null
            if (id in keptIds || stored.status(index) == StepStatus.LOST) null else documents[location.file]?.let { marker(id, location, it) }
        }
        tracked = stored.key to kept + created
    }

    /** The lines that the markers followed and the texts of their documents, read together. */
    fun snapshot(): LiveSnapshot? = ApplicationManager.getApplication().runReadAction(Computable {
        synchronized(this) {
            val (storyKey, markers) = tracked ?: return@Computable null
            val valid = markers.filter { it.marker.isValid }
            LiveSnapshot(storyKey, ranges(valid), valid.associate { it.path to it.marker.document.immutableCharSequence })
        }
    })

    @Synchronized
    fun relocated(previous: StoredStory, current: StoredStory) {
        tracked?.takeIf { it.first == previous.key }?.let { tracked = current.key to it.second }
    }

    /** Removes the markers. Call on the EDT. */
    @Synchronized
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
    private fun marker(stepId: String, location: Location, source: SourceDocument): Marker? {
        val document = source.document
        if (location.startLine < 1 || location.endLine < location.startLine || location.endLine > document.lineCount) return null
        val marker = document.createRangeMarker(document.getLineStartOffset(location.startLine - 1), document.getLineEndOffset(location.endLine - 1), true)
        return Marker(stepId, location.file, location.rangeText, source.path, marker)
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
