package io.github.hubertrybak.codestory.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

sealed interface ValidationResult {
    class Valid(val story: Story) : ValidationResult
    class Invalid(val errors: List<ReportItem>) : ValidationResult
}

/**
 * Checks a story as JSON. Any error rejects the whole story, and the result lists every error
 * that the checks find. Without [files], only the structure is checked. The size limit applies
 * to the saved file, so [StoryFolder.save] checks it.
 */
class StoryValidator(private val files: StoryFiles?) {

    fun validate(input: JsonElement): ValidationResult {
        val errors = mutableListOf<ReportItem>()
        val story = Checker(errors).story(input)
        return if (story != null && errors.isEmpty()) ValidationResult.Valid(story) else ValidationResult.Invalid(errors)
    }

    private class Owner(val step: Int?) {
        val subject = if (step == null) "The story" else "Step $step"
        val where = if (step == null) "In the story" else "In step $step"
    }

    private inner class Checker(private val errors: MutableList<ReportItem>) {

        fun story(input: JsonElement): Story? {
            val owner = Owner(null)
            if (input !is JsonObject) {
                return error(owner, ErrorCode.WRONG_TYPE, "The story must be a JSON object.", STORY_HINT)
            }
            val title = text(input, "title", owner, TITLE_HINT)
            val steps = steps(owner, input)
            return if (title != null && steps != null) Story(title, steps) else null
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
            return if (steps.isNotEmpty() && steps.all { it != null }) steps.filterNotNull() else null
        }

        private fun step(owner: Owner, element: JsonElement): Step? {
            if (element !is JsonObject) {
                return error(owner, ErrorCode.WRONG_TYPE, "Step ${owner.step} must be a JSON object.", STEP_HINT)
            }
            val title = text(element, "title", owner, STEP_TITLE_HINT)
            val comment = text(element, "comment", owner, COMMENT_HINT)
            val location = location(owner, element)
            return if (title != null && comment != null && location != null) Step(title, comment, location) else null
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
            if (start == null || end == null || reversed) return null
            if (found == null) return Location(file, start, end)
            val lineCount = TextFiles.lineCount(found.text)
            if (end > lineCount) {
                return if (lineCount == 0) {
                    error(owner, ErrorCode.LINES_OUTSIDE_FILE, "${owner.where}, the file `${quote(file)}` is empty.", "Point the step at a file that has code.")
                } else {
                    error(
                        owner, ErrorCode.LINES_OUTSIDE_FILE,
                        "${owner.where}, lines $start-$end are outside `${quote(file)}`. The file has $lineCount lines.",
                        "Read the file again and use line numbers from 1 to $lineCount.",
                    )
                }
            }
            // The real path keeps the letter case of the file system and matches Git.
            return Location(found.path, start, end)
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

        private const val STORY_HINT = "Send the story as an object with `title` and `steps`."
        private const val TITLE_HINT = "Give the story a short `title` (8 words or fewer)."
        private const val STEPS_HINT = "Add `steps`: a list of 3 to 12 steps (15 at most). Each step explains one place in the code."
        private const val STEP_HINT = "Send each step as an object with `title`, `comment` and `location`."
        private const val STEP_TITLE_HINT = "Give the step a short `title` (8 words or fewer)."
        private const val COMMENT_HINT = "Add a `comment` that explains the code of the step in 1 to 3 short sentences."
        private const val LOCATION_HINT = "Add a `location` with `file`, `startLine` and `endLine`."
        private const val FILE_HINT = "Set `location.file` to the path of the file, relative to the repository root."
        private const val START_LINE_HINT = "Set `location.startLine` to the first line of the range. The first line of a file is line 1."
        private const val END_LINE_HINT = "Set `location.endLine` to the last line of the range. The range includes this line."
        private const val RELATIVE_PATH_HINT = "Use a path relative to the repository root, with `/` between folders, for example `src/App.kt`."

        private fun quote(text: String): String = if (text.length <= MAX_QUOTE) text else text.take(MAX_QUOTE) + "…"
    }
}
