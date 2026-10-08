package io.github.hubertrybak.codestory.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

sealed interface ValidationResult {
    /** [corrections] lists the line numbers that the anchor check corrected. */
    class Valid(val story: Story, val corrections: List<ReportItem> = emptyList()) : ValidationResult
    class Invalid(val errors: List<ReportItem>, val corrections: List<ReportItem> = emptyList()) : ValidationResult
}

/**
 * Checks a story as JSON. Any error rejects the whole story, and the result lists every error
 * that the checks find. Without [files], only the structure is checked. With [files], the anchor
 * text of each location must be at its start line or nearby: a match nearby moves the range and
 * adds a correction, and the code of the range is stored in the story. The size limit applies
 * to the saved file, so [StoryFolder.save] checks it.
 */
class StoryValidator(private val files: StoryFiles?, private val readMetadata: Boolean = false) {

    fun validate(input: JsonElement): ValidationResult {
        val errors = mutableListOf<ReportItem>()
        val corrections = mutableListOf<ReportItem>()
        val story = Checker(errors, corrections).story(input)
        return if (story != null && errors.isEmpty()) ValidationResult.Valid(story, corrections)
            else ValidationResult.Invalid(errors, corrections)
    }

    private class Owner(val step: Int?) {
        val subject = if (step == null) "The story" else "Step $step"
        val where = if (step == null) "In the story" else "In step $step"
    }

    private inner class Checker(private val errors: MutableList<ReportItem>, private val corrections: MutableList<ReportItem>) {

        fun story(input: JsonElement): Story? {
            val owner = Owner(null)
            if (input !is JsonObject) {
                return error(owner, ErrorCode.WRONG_TYPE, "The story must be a JSON object.", STORY_HINT)
            }
            val title = text(input, "title", owner, TITLE_HINT)
            val type = storyType(input, owner)
            val summary = text(input, "summary", owner, "Give the story a summary in 1 to 3 short sentences.")
            val request = text(input, "request", owner, "Include the original Code Story request.")
            val author = author(input, owner)
            // The agent cannot set plugin-owned creation metadata.
            val createdAt = if (readMetadata) createdAt(input, owner) else null
            val git = if (readMetadata) git(input, owner) else StoryGit()
            val baseRef = baseRef(input, owner)
            val delivery = if (readMetadata) nullableText(input, "delivery", owner, "Omit delivery for file-drop stories, or use mcp.") else null
            if (delivery != null && delivery != StoryFormat.MCP_DELIVERY) {
                error(owner, ErrorCode.INVALID_VALUE, "The story has an unknown delivery marker.", "Omit delivery for file-drop stories, or use mcp.")
            }
            val steps = steps(owner, input)
            return if (title != null && type != null && summary != null && request != null && author != null && steps != null) {
                Story(title, type, summary, request, author, steps, createdAt, git, baseRef, delivery)
            } else null
        }

        /** Git gets the base as an argument, so a story file can name a commit only, never an option or a path. */
        private fun baseRef(story: JsonObject, owner: Owner): String? {
            val value = nullableText(story, "baseRef", owner, BASE_REF_HINT) ?: return null
            if (baseRefPattern.matches(value)) return value
            return error(owner, ErrorCode.INVALID_VALUE, "The story `baseRef` `${quote(value)}` is not a commit SHA or `HEAD`.", BASE_REF_HINT)
        }

        private fun storyType(story: JsonObject, owner: Owner): StoryType? {
            val value = text(story, "type", owner, TYPE_HINT) ?: return null
            return StoryType.entries.firstOrNull { it.value == value }
                ?: error(owner, ErrorCode.INVALID_VALUE, "The story has an unknown `type`: `${quote(value)}`.", TYPE_HINT)
        }

        private fun author(story: JsonObject, owner: Owner): StoryAuthor? {
            val author = obj(story, "author", owner, "Add `author` with your agent name and model.") ?: return null
            val agent = text(author, "agent", owner, "Set `author.agent` to your agent name.", "author.agent")
            val model = text(author, "model", owner, "Set `author.model` to your model name.", "author.model")
            return if (agent != null && model != null) StoryAuthor(agent, model) else null
        }

        private fun createdAt(story: JsonObject, owner: Owner): Instant? {
            val hint = "Use an ISO 8601 creation time, for example `2026-10-05T12:00:00Z`."
            val value = text(story, "createdAt", owner, hint) ?: return null
            return try {
                Instant.parse(value)
            } catch (_: DateTimeParseException) {
                error(owner, ErrorCode.INVALID_VALUE, "The story has an invalid `createdAt`.", hint)
            }
        }

        private fun git(story: JsonObject, owner: Owner): StoryGit {
            val hint = "Add `git` with `branch` and `commit`; use null when Git is unavailable."
            val git = obj(story, "git", owner, hint) ?: return StoryGit()
            return StoryGit(
                nullableText(git, "branch", owner, hint, "git.branch", required = true),
                nullableText(git, "commit", owner, hint, "git.commit", required = true),
            )
        }

        private fun steps(owner: Owner, story: JsonObject): List<Step>? {
            val array = when (val element = story["steps"]) {
                is JsonArray -> element
                null, JsonNull -> return error(owner, ErrorCode.MISSING_FIELD, "The story has no `steps`.", STEPS_HINT)
                else -> return error(owner, ErrorCode.WRONG_TYPE, "In the story, `steps` must be a list.", STEPS_HINT)
            }
            if (array.isEmpty()) error(owner, ErrorCode.NO_STEPS, "The story has no steps.", STEPS_HINT)
            if (array.size > StoryLimits.MAX_STEPS) {
                error(
                    owner, ErrorCode.TOO_MANY_STEPS,
                    "The story has ${array.size} steps. The limit is ${StoryLimits.MAX_STEPS}.",
                    "Keep the 3 to 12 most important steps, or split the subject into several stories.",
                )
            }
            // Steps after the limit must go anyway, so they are not checked.
            val steps = array.take(StoryLimits.MAX_STEPS).mapIndexed { index, element -> step(Owner(index + 1), element) }
            val ids = mutableSetOf<String>()
            steps.forEachIndexed { index, step ->
                if (step?.id != null && !ids.add(step.id)) {
                    error(Owner(index + 1), ErrorCode.DUPLICATE_STEP_ID, "Step ${index + 1} repeats the ID `${quote(step.id)}`.", "Give every step a unique ID, or omit IDs and let the plugin assign them.")
                }
            }
            return if (steps.isNotEmpty() && steps.all { it != null }) steps.filterNotNull() else null
        }

        private fun step(owner: Owner, element: JsonElement): Step? {
            if (element !is JsonObject) {
                return error(owner, ErrorCode.WRONG_TYPE, "Step ${owner.step} must be a JSON object.", STEP_HINT)
            }
            val title = text(element, "title", owner, STEP_TITLE_HINT)
            val comment = text(element, "comment", owner, COMMENT_HINT)
            val id = if (readMetadata) text(element, "id", owner, ID_HINT) else nullableText(element, "id", owner, ID_HINT)
            val location = if (element["location"] == null || element["location"] == JsonNull) null else location(owner, element)
            // `children` and `links` are reserved for later modes and ignored in v1.
            return if (title != null && comment != null) Step(title, comment, location, id) else null
        }

        private fun obj(parent: JsonObject, name: String, owner: Owner, hint: String): JsonObject? = when (val element = parent[name]) {
            is JsonObject -> element
            null, JsonNull -> error(owner, ErrorCode.MISSING_FIELD, "${owner.subject} has no `$name`.", hint)
            else -> error(owner, ErrorCode.WRONG_TYPE, "${owner.where}, `$name` must be a JSON object.", hint)
        }

        private fun nullableText(obj: JsonObject, name: String, owner: Owner, hint: String, label: String = name, required: Boolean = false): String? {
            if (name !in obj) {
                if (required) error(owner, ErrorCode.MISSING_FIELD, "${owner.subject} has no `$label`.", hint)
                return null
            }
            return if (obj[name] == JsonNull) null else text(obj, name, owner, hint, label)
        }

        private fun location(owner: Owner, step: JsonObject): Location? {
            val location = when (val element = step["location"]) {
                is JsonObject -> element
                null, JsonNull -> return error(owner, ErrorCode.MISSING_FIELD, "${owner.subject} has no `location`.", LOCATION_HINT)
                else -> return error(owner, ErrorCode.WRONG_TYPE, "${owner.where}, `location` must be a JSON object.", LOCATION_HINT)
            }
            val file = text(location, "file", owner, FILE_HINT, label = "location.file")?.let { path(owner, it) }
            val start = line(location, "startLine", owner, START_LINE_HINT)
            val end = line(location, "endLine", owner, END_LINE_HINT)
            val anchorText = anchorText(owner, location)
            // The plugin stores the code of the range itself; an agent cannot set it.
            val rangeText = if (readMetadata) storedCode(owner, location) else null
            val reversed = start != null && end != null && end < start
            if (reversed) {
                error(
                    owner, ErrorCode.REVERSED_RANGE,
                    "${owner.where}, `location.endLine` ($end) is before `location.startLine` ($start).",
                    "Make `location.endLine` equal to or greater than `location.startLine`.",
                )
            }
            if (file == null) return null
            val found = if (files == null) null else lookUp(owner, file) ?: return null
            if (start == null || end == null || reversed || (anchorText == null && !readMetadata)) return null
            if (found == null) return Location(file, start, end, anchorText, rangeText)
            val lines = TextFiles.lines(found.text)
            if (lines.isEmpty()) {
                return error(owner, ErrorCode.LINES_OUTSIDE_FILE, "${owner.where}, the file `${quote(file)}` is empty.", "Point the step at a file that has code.")
            }
            // A story file without anchors keeps its lines.
            val first = if (anchorText == null) start else anchoredStart(owner, file, lines, start, end, anchorText) ?: return null
            val last = first + (end - start)
            if (last > lines.size) {
                return if (first == start) outside(owner, file, start, end, lines.size) else error(
                    owner, ErrorCode.LINES_OUTSIDE_FILE,
                    "${owner.where}, `location.anchorText` is at line $first, so the range ends at line $last. `${quote(file)}` has ${lines.size} lines.",
                    "Read the file again. Then set `location.startLine` and `location.endLine` to the first and the last line of the range.",
                )
            }
            if (first != start) {
                corrections += ReportItem(
                    owner.step, CorrectionCode.LINES_SHIFTED.code,
                    "Step ${owner.step}: ${lineRange(start, end)} changed to ${if (first == last) "$first" else "$first–$last"}.",
                    "`location.anchorText` is at line $first, not at line $start. The saved story uses the corrected lines.",
                )
            }
            val code = if (anchorText == null) rangeText else AnchorPolicy.rangeText(lines, first, last)
            // The real path keeps the letter case of the file system and matches Git.
            return Location(found.path, first, last, anchorText, code)
        }

        /** The first line of the range: where the anchor text is, at the given line or nearby. `null` after an error. */
        private fun anchoredStart(owner: Owner, file: String, lines: List<String>, start: Int, end: Int, anchorText: String): Int? =
            when (val verification = AnchorPolicy.verify(lines, start, anchorText)) {
                AnchorPolicy.Verification.InPlace -> start
                is AnchorPolicy.Verification.Shifted -> verification.startLine
                is AnchorPolicy.Verification.Ambiguous -> error(
                    owner, ErrorCode.ANCHOR_AMBIGUOUS,
                    "${owner.where}, `location.anchorText` is not at line $start of `${quote(file)}`. " +
                        "It is at lines ${verification.startLines.joinToString(" and ")}, at the same distance.",
                    "Read the file again and set `location.startLine` to the first line of the range.",
                )
                AnchorPolicy.Verification.NotFound -> if (start > lines.size) outside(owner, file, start, end, lines.size) else {
                    val actual = lines[start - 1].trim()
                    error(
                        owner, ErrorCode.ANCHOR_NOT_FOUND,
                        "${owner.where}, `location.anchorText` is not at line $start of `${quote(file)}` or within ${AnchorPolicy.NEARBY_LINES} lines of it. " +
                            if (actual.isEmpty()) "Line $start is empty." else "Line $start is `${quote(actual)}`.",
                        "Read the file again. Set `location.startLine` to the first line of the range, and copy that line into `location.anchorText`.",
                    )
                }
            }

        private fun outside(owner: Owner, file: String, start: Int, end: Int, lineCount: Int): Nothing? = error(
            owner, ErrorCode.LINES_OUTSIDE_FILE,
            "${owner.where}, lines $start-$end are outside `${quote(file)}`. The file has $lineCount lines.",
            "Read the file again and use line numbers from 1 to $lineCount.",
        )

        private fun anchorText(owner: Owner, location: JsonObject): String? {
            val label = "location.anchorText"
            // An agent must send the anchor text. Story files saved without anchors can still be read.
            val text = if (readMetadata) nullableText(location, "anchorText", owner, ANCHOR_HINT, label)
                else text(location, "anchorText", owner, ANCHOR_HINT, label)
            if (text == null || text.none { it == '\n' || it == '\r' }) return text
            return error(owner, ErrorCode.INVALID_VALUE, "${owner.where}, `$label` has more than one line.", "Copy only the text of line `location.startLine` into `$label`.")
        }

        /** The code of the range as the plugin stored it, with indentation and empty lines. */
        private fun storedCode(owner: Owner, location: JsonObject): String? {
            val element = location["rangeText"]
            return when {
                element == null || element is JsonNull -> null
                element is JsonPrimitive && element.isString -> element.content
                else -> error(owner, ErrorCode.WRONG_TYPE, "${owner.where}, `location.rangeText` must be text.", "Remove `location.rangeText`. The plugin stores the code of the range.")
            }
        }

        private fun path(owner: Owner, raw: String): String? = when (val result = StoryPaths.normalize(raw)) {
            is StoryPaths.Result.Valid -> result.path
            StoryPaths.Result.Absolute -> error(owner, ErrorCode.ABSOLUTE_PATH, "${owner.where}, `${quote(raw)}` is an absolute path.", RELATIVE_PATH_HINT)
            StoryPaths.Result.Parent -> error(owner, ErrorCode.PARENT_PATH, "${owner.where}, the path `${quote(raw)}` contains `..`.", "Use a path from the repository root without `..`.")
            StoryPaths.Result.Invalid -> error(owner, ErrorCode.INVALID_PATH, "${owner.where}, `${quote(raw)}` is not a valid file path.", RELATIVE_PATH_HINT)
        }

        /** Looks up the file, or returns `null` after an error. */
        private fun lookUp(owner: Owner, path: String): FileLookup.Found? {
            val files = checkNotNull(files)
            val shown = quote(path)
            return when (val found = files.lookup(path)) {
                is FileLookup.Found -> found
                FileLookup.NotFound -> error(
                    owner, ErrorCode.FILE_NOT_FOUND, "${owner.where}, the file `$shown` does not exist.",
                    "Read the file to make sure that the path is correct. Paths are relative to the repository root `${files.rootLabel}`.",
                )
                FileLookup.NotAFile -> error(owner, ErrorCode.NOT_A_FILE, "${owner.where}, `$shown` is not a file.", "Point the step at a file, not at a folder.")
                FileLookup.NotText -> error(owner, ErrorCode.NOT_A_TEXT_FILE, "${owner.where}, `$shown` is not a text file.", "Point the step at a source file.")
                FileLookup.OutsideRoot -> error(
                    owner, ErrorCode.OUTSIDE_PROJECT, "${owner.where}, `$shown` leads out of the project.",
                    "Point the step at a file in the repository. Do not use a symbolic link that leads out of it.",
                )
                FileLookup.InvalidPath -> error(owner, ErrorCode.INVALID_PATH, "${owner.where}, `$shown` is not a valid file path.", RELATIVE_PATH_HINT)
            }
        }

        private fun text(obj: JsonObject, name: String, owner: Owner, hint: String, label: String = name): String? {
            val element = obj[name]
            return when {
                element == null || element is JsonNull -> error(owner, ErrorCode.MISSING_FIELD, "${owner.subject} has no `$label`.", hint)
                element !is JsonPrimitive || !element.isString -> error(owner, ErrorCode.WRONG_TYPE, "${owner.where}, `$label` must be text.", hint)
                element.content.isBlank() -> error(owner, ErrorCode.EMPTY_TEXT, "${owner.subject} has an empty `$label`.", hint)
                else -> element.content.trim()
            }
        }

        private fun line(location: JsonObject, name: String, owner: Owner, hint: String): Int? {
            val element = location[name]
            val label = "location.$name"
            val value = (element as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            return when {
                element == null || element is JsonNull -> error(owner, ErrorCode.MISSING_FIELD, "${owner.subject} has no `$label`.", hint)
                value == null -> error(owner, ErrorCode.WRONG_TYPE, "${owner.where}, `$label` must be a whole number.", hint)
                value < 1 -> error(owner, ErrorCode.INVALID_LINE, "${owner.where}, `$label` is $value. Line numbers start at 1.", hint)
                else -> value
            }
        }

        private fun error(owner: Owner, code: ErrorCode, message: String, hint: String): Nothing? {
            errors += ReportItem(owner.step, code, message, hint)
            return null
        }
    }

    companion object {
        private const val MAX_QUOTE = 200
        private val baseRefPattern = Regex("HEAD|[0-9a-fA-F]{4,64}")

        private const val STORY_HINT = "Send the story as an object with `title`, `type`, `summary`, `request`, `author` and `steps`."
        private const val TYPE_HINT = "Set `type` to `change`, `exploration`, `bug-trace` or `review`."
        private const val BASE_REF_HINT = "Set `baseRef` to the SHA of the commit before the change, or to `HEAD` for uncommitted work. Omit it for a story that is not about a change."
        private const val ID_HINT = "Give the step a stable, nonempty `id`. Keep it when you revise the step."
        private const val TITLE_HINT = "Give the story a short `title` (8 words or fewer)."
        private const val STEPS_HINT = "Add `steps`: a list of 3 to 12 steps (15 at most). Each step explains one idea."
        private const val STEP_HINT = "Send each step as an object with `title`, `comment` and an optional `location`."
        private const val STEP_TITLE_HINT = "Give the step a short `title` (8 words or fewer)."
        private const val COMMENT_HINT = "Add a `comment` that explains the code of the step in 1 to 3 short sentences."
        private const val LOCATION_HINT = "Add a `location` with `file`, `startLine`, `endLine` and `anchorText`."
        private const val ANCHOR_HINT = "Copy the exact text of line `location.startLine` into `location.anchorText`. Start the range at a line with code."
        private const val FILE_HINT = "Set `location.file` to the path of the file, relative to the repository root."
        private const val START_LINE_HINT = "Set `location.startLine` to the first line of the range. The first line of a file is line 1."
        private const val END_LINE_HINT = "Set `location.endLine` to the last line of the range. The range includes this line."
        private const val RELATIVE_PATH_HINT = "Use a path relative to the repository root, with `/` between folders, for example `src/App.kt`."

        private fun quote(text: String): String = if (text.length <= MAX_QUOTE) text else text.take(MAX_QUOTE) + "…"

        private fun lineRange(start: Int, end: Int): String = if (start == end) "line $start" else "lines $start–$end"
    }
}
