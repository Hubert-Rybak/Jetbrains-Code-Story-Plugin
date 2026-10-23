package io.github.hubertrybak.codestory.core

import io.github.hubertrybak.codestory.core.StoryJson.jsonText
import io.github.hubertrybak.codestory.core.StoryJson.quoted
import io.github.hubertrybak.codestory.core.StoryJson.step
import io.github.hubertrybak.codestory.core.StoryJson.story
import io.github.hubertrybak.codestory.core.StoryJson.withoutField
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** One case per validation rule: the story JSON and the errors it must give, as (step, code). */
class ValidationCase(
    private val name: String,
    val json: String,
    val expected: List<Pair<Int?, ErrorCode>>,
    val needsSymlinks: Boolean = false,
) {
    override fun toString() = name
}

object ValidationCases {
    private fun valid(name: String, json: String, needsSymlinks: Boolean = false) = ValidationCase(name, json, emptyList(), needsSymlinks)

    private fun invalid(name: String, json: String, vararg expected: Pair<Int?, ErrorCode>, needsSymlinks: Boolean = false) =
        ValidationCase(name, json, expected.toList(), needsSymlinks)

    val all: List<ValidationCase> = listOf(
        valid("valid story", story(step(), step(start = "1", end = "10"))),
        valid("one-line range", story(step(start = "10", end = "10"))),
        valid("CRLF file", story(step(file = quoted("src/Windows.kt"), start = "1", end = "3", anchor = quoted("one")))),
        valid("anchor with other indentation and spaces", story(step(anchor = "\"\\t  line 2   \""))),
        valid("anchor a few lines above", story(step(start = "5", end = "7", anchor = quoted("line 2")))),
        valid("anchor a few lines below", story(step(start = "2", end = "4", anchor = quoted("line 7")))),
        valid("anchor 20 lines away", story(step(file = quoted("src/Long.kt"), start = "30", end = "31", anchor = quoted("line 10")))),
        valid("anchor in a CRLF file", story(step(file = quoted("src/Windows.kt"), start = "1", end = "2", anchor = quoted("two")))),
        valid("start line after the end, anchor nearby", story(step(start = "14", end = "15", anchor = quoted("line 9")))),
        valid("rangeText from an agent is ignored", story(step(location = """{"file": "src/App.kt", "startLine": 2, "endLine": 2, "anchorText": "line 2", "rangeText": 7}"""))),
        valid("symbolic link inside the project", story(step(file = quoted("inside-link.kt"))), needsSymlinks = true),
        valid("text-only step", story(step(location = null))),
        valid("null location", story(step(location = "null"))),
        valid("stable step ID", story(step(id = quoted("entry")))),
        valid("reserved fields are ignored", story("""{"title": "Overview", "comment": "Text.", "children": [{}], "links": ["later"]}""")),
        *StoryType.entries.map { valid("type ${it.value}", story(step(), type = quoted(it.value))) }.toTypedArray(),
        valid("base HEAD for uncommitted work", story(step(), baseRef = quoted("HEAD"))),
        valid("base commit SHA", story(step(), baseRef = quoted("0123456789abcdef0123456789ABCDEF01234567"))),
        valid("short base commit SHA", story(step(), baseRef = quoted("0a1b2c3"))),
        valid("null base", story(step(), baseRef = "null")),
        *StepKind.entries.map { valid("kind ${it.value}", story(step(), step(kind = quoted(it.value)))) }.toTypedArray(),
        valid("null kind", story(step(kind = "null"))),
        valid("context before the first explanation", story(step(kind = quoted("context")), step())),
        valid("text-only context", story(step(), step(kind = quoted("context"), location = null))),
        valid("proposal with code for its lines", story(step(kind = quoted("proposal"), proposedCode = jsonText("line 2\n    retry()")))),
        valid("proposal that removes its lines", story(step(kind = quoted("proposal"), proposedCode = quoted("")))),
        valid("proposal of new code without a location", story(step(kind = quoted("proposal"), location = null, proposedCode = quoted("fun retry() {}")))),
        valid("proposal in words only", story(step(kind = quoted("proposal"), location = null))),
        valid("null proposed code", story(step(proposedCode = "null"))),

        invalid("story is not an object", "[]", null to ErrorCode.WRONG_TYPE),
        invalid("no title", withoutField(story(step()), "title"), null to ErrorCode.MISSING_FIELD),
        invalid("null title", story(step(), title = "null"), null to ErrorCode.MISSING_FIELD),
        invalid("title is not text", story(step(), title = "42"), null to ErrorCode.WRONG_TYPE),
        invalid("blank title", story(step(), title = quoted("  ")), null to ErrorCode.EMPTY_TEXT),
        invalid("no steps field", withoutField(story(step()), "steps"), null to ErrorCode.MISSING_FIELD),
        invalid("steps is not a list", story().replace("\"steps\": []", "\"steps\": {}"), null to ErrorCode.WRONG_TYPE),
        invalid("no story type", withoutField(story(step()), "type"), null to ErrorCode.MISSING_FIELD),
        invalid("unknown story type", story(step(), type = quoted("tour")), null to ErrorCode.INVALID_VALUE),
        invalid("type is not text", story(step(), type = "7"), null to ErrorCode.WRONG_TYPE),
        invalid("no summary", withoutField(story(step()), "summary"), null to ErrorCode.MISSING_FIELD),
        invalid("blank summary", story(step(), summary = quoted(" ")), null to ErrorCode.EMPTY_TEXT),
        invalid("no request", withoutField(story(step()), "request"), null to ErrorCode.MISSING_FIELD),
        invalid("blank request", story(step(), request = quoted(" ")), null to ErrorCode.EMPTY_TEXT),
        invalid("base is a branch name", story(step(), baseRef = quoted("main")), null to ErrorCode.INVALID_VALUE),
        invalid("base is a Git option", story(step(), baseRef = quoted("--output=/tmp/story")), null to ErrorCode.INVALID_VALUE),
        invalid("base names a file", story(step(), baseRef = quoted("HEAD:src/App.kt")), null to ErrorCode.INVALID_VALUE),
        invalid("base is not text", story(step(), baseRef = "42"), null to ErrorCode.WRONG_TYPE),
        invalid("blank base", story(step(), baseRef = quoted(" ")), null to ErrorCode.EMPTY_TEXT),
        invalid("no author", withoutField(story(step()), "author"), null to ErrorCode.MISSING_FIELD),
        invalid("author is not an object", story(step(), author = "[]"), null to ErrorCode.WRONG_TYPE),
        invalid("no agent", story(step(), author = """{"model": "test"}"""), null to ErrorCode.MISSING_FIELD),
        invalid("no model", story(step(), author = """{"agent": "Test Agent"}"""), null to ErrorCode.MISSING_FIELD),
        invalid("blank agent", story(step(), author = """{"agent": " ", "model": "test"}"""), null to ErrorCode.EMPTY_TEXT),
        invalid("model is not text", story(step(), author = """{"agent": "Test Agent", "model": 3}"""), null to ErrorCode.WRONG_TYPE),
        invalid("empty step ID", story(step(id = quoted(""))), 1 to ErrorCode.EMPTY_TEXT),
        invalid("step ID is not text", story(step(id = "4")), 1 to ErrorCode.WRONG_TYPE),
        invalid("duplicate step IDs", story(step(id = quoted("entry")), step(id = quoted("entry"))), 2 to ErrorCode.DUPLICATE_STEP_ID),
        invalid("empty story", story(), null to ErrorCode.NO_STEPS),
        invalid("more than 15 steps", story(*Array(16) { step() }), null to ErrorCode.TOO_MANY_STEPS),
        invalid(
            "steps after the limit are not checked",
            story(*Array(15) { step() }, step(file = quoted("missing.kt"))),
            null to ErrorCode.TOO_MANY_STEPS,
        ),
        invalid("step is not an object", story("\"step\""), 1 to ErrorCode.WRONG_TYPE),
        invalid("unknown step kind", story(step(), step(kind = quoted("note"))), 2 to ErrorCode.INVALID_VALUE),
        invalid("step kind is not text", story(step(kind = "1")), 1 to ErrorCode.WRONG_TYPE),
        invalid("blank step kind", story(step(kind = quoted(" "))), 1 to ErrorCode.EMPTY_TEXT),
        invalid("proposed code in an explanation", story(step(proposedCode = quoted("retry()"))), 1 to ErrorCode.INVALID_VALUE),
        invalid("proposed code in context", story(step(kind = quoted("context"), proposedCode = quoted("retry()"))), 1 to ErrorCode.INVALID_VALUE),
        invalid("proposed code is not text", story(step(kind = quoted("proposal"), proposedCode = "[\"retry()\"]")), 1 to ErrorCode.WRONG_TYPE),
        invalid("empty proposal without a location", story(step(kind = quoted("proposal"), location = null, proposedCode = jsonText("\n"))), 1 to ErrorCode.EMPTY_TEXT),
        invalid("no step title", story("""{"comment": "Text.", "location": {"file": "src/App.kt", "startLine": 1, "endLine": 2, "anchorText": "line 1"}}"""), 1 to ErrorCode.MISSING_FIELD),
        invalid("empty comment", story(step(comment = quoted(""))), 1 to ErrorCode.EMPTY_TEXT),
        invalid("comment is not text", story(step(comment = "[\"a\"]")), 1 to ErrorCode.WRONG_TYPE),
        invalid("location is not an object", story("""{"title": "Entry point", "comment": "Text.", "location": "src/App.kt:2"}"""), 1 to ErrorCode.WRONG_TYPE),
        invalid("multiple locations", story(step(location = """[{"file": "src/App.kt", "startLine": 1, "endLine": 2}]""")), 1 to ErrorCode.WRONG_TYPE),
        invalid("no file", story("""{"title": "T", "comment": "C.", "location": {"startLine": 1, "endLine": 2, "anchorText": "line 1"}}"""), 1 to ErrorCode.MISSING_FIELD),
        invalid("absolute path", story(step(file = quoted("/etc/passwd"))), 1 to ErrorCode.ABSOLUTE_PATH),
        invalid("Windows absolute path", story(step(file = quoted("C:\\repo\\src\\App.kt"))), 1 to ErrorCode.ABSOLUTE_PATH),
        invalid("UNC path", story(step(file = quoted("\\\\server\\share\\App.kt"))), 1 to ErrorCode.ABSOLUTE_PATH),
        invalid("file URI", story(step(file = quoted("file:///repo/src/App.kt"))), 1 to ErrorCode.ABSOLUTE_PATH),
        invalid("path out of the project", story(step(file = quoted("../outside/secret.kt"))), 1 to ErrorCode.PARENT_PATH),
        invalid("dot-dot inside the project", story(step(file = quoted("src/../src/App.kt"))), 1 to ErrorCode.PARENT_PATH),
        invalid("control character in path", story(step(file = "\"src/App\\u0000.kt\"")), 1 to ErrorCode.INVALID_PATH),
        invalid("only dots", story(step(file = quoted("./."))), 1 to ErrorCode.INVALID_PATH),
        invalid("symbolic link out of the project", story(step(file = quoted("outside-link.kt"), start = "1", end = "1")), 1 to ErrorCode.OUTSIDE_PROJECT, needsSymlinks = true),
        invalid("folder link out of the project", story(step(file = quoted("outside-dir/secret.kt"), start = "1", end = "1")), 1 to ErrorCode.OUTSIDE_PROJECT, needsSymlinks = true),
        invalid("file does not exist", story(step(file = quoted("src/Missing.kt"))), 1 to ErrorCode.FILE_NOT_FOUND),
        invalid("folder instead of a file", story(step(file = quoted("docs"))), 1 to ErrorCode.NOT_A_FILE),
        invalid("binary file", story(step(file = quoted("src/logo.png"), start = "1", end = "1")), 1 to ErrorCode.NOT_A_TEXT_FILE),
        invalid("no start line", story("""{"title": "T", "comment": "C.", "location": {"file": "src/App.kt", "endLine": 2, "anchorText": "line 1"}}"""), 1 to ErrorCode.MISSING_FIELD),
        invalid("start line as text", story(step(start = "\"2\"")), 1 to ErrorCode.WRONG_TYPE),
        invalid("fractional start line", story(step(start = "2.5")), 1 to ErrorCode.WRONG_TYPE),
        invalid("start line 0", story(step(start = "0")), 1 to ErrorCode.INVALID_LINE),
        invalid("negative end line", story(step(start = "1", end = "-1")), 1 to ErrorCode.INVALID_LINE),
        invalid("end before start", story(step(start = "5", end = "3")), 1 to ErrorCode.REVERSED_RANGE),
        invalid("end after the last line", story(step(start = "8", end = "11")), 1 to ErrorCode.LINES_OUTSIDE_FILE),
        invalid("largest end line in place", story(step(start = "1", end = Int.MAX_VALUE.toString())), 1 to ErrorCode.LINES_OUTSIDE_FILE),
        invalid("largest end line shifted up", story(step(start = "3", end = Int.MAX_VALUE.toString(), anchor = quoted("line 2"))), 1 to ErrorCode.LINES_OUTSIDE_FILE),
        invalid("range after the last line", story(step(start = "40", end = "45")), 1 to ErrorCode.LINES_OUTSIDE_FILE),
        invalid("final line break is not a line", story(step(file = quoted("src/Windows.kt"), start = "4", end = "4")), 1 to ErrorCode.LINES_OUTSIDE_FILE),
        invalid("empty file", story(step(file = quoted("src/empty.txt"), start = "1", end = "1")), 1 to ErrorCode.LINES_OUTSIDE_FILE),
        invalid("no anchor text", story(step(anchor = null)), 1 to ErrorCode.MISSING_FIELD),
        invalid("null anchor text", story(step(anchor = "null")), 1 to ErrorCode.MISSING_FIELD),
        invalid("blank anchor text", story(step(anchor = quoted("   "))), 1 to ErrorCode.EMPTY_TEXT),
        invalid("anchor text is not text", story(step(anchor = "7")), 1 to ErrorCode.WRONG_TYPE),
        invalid("anchor text of two lines", story(step(anchor = "\"line 2\\nline 3\"")), 1 to ErrorCode.INVALID_VALUE),
        invalid("anchor text not in the file", story(step(anchor = quoted("fun missing()"))), 1 to ErrorCode.ANCHOR_NOT_FOUND),
        invalid("anchor text more than 20 lines away", story(step(file = quoted("src/Long.kt"), start = "31", end = "31", anchor = quoted("line 10"))), 1 to ErrorCode.ANCHOR_NOT_FOUND),
        invalid("anchor text only inside other words", story(step(anchor = quoted("line"))), 1 to ErrorCode.ANCHOR_NOT_FOUND),
        invalid("anchor text at the same distance above and below", story(step(file = quoted("src/Twice.kt"), start = "4", end = "4", anchor = quoted("return"))), 1 to ErrorCode.ANCHOR_AMBIGUOUS),
        invalid("anchor nearby, but the range then ends after the file", story(step(start = "5", end = "11", anchor = quoted("line 8"))), 1 to ErrorCode.LINES_OUTSIDE_FILE),
        invalid("start line after the end, no anchor nearby", story(step(start = "40", end = "41", anchor = quoted("missing"))), 1 to ErrorCode.LINES_OUTSIDE_FILE),
        invalid(
            "every error of every step",
            story(step(), step(file = quoted("src/Missing.kt"), start = "0"), step(start = "7", end = "2"), title = quoted("")),
            null to ErrorCode.EMPTY_TEXT,
            2 to ErrorCode.INVALID_LINE,
            2 to ErrorCode.FILE_NOT_FOUND,
            3 to ErrorCode.REVERSED_RANGE,
        ),
    )
}

@RunWith(Parameterized::class)
class StoryValidatorTest(private val case: ValidationCase) {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var files: FixtureFiles

    @Before
    fun setUp() {
        files = FixtureFiles(temp.newFolder("repo").toPath(), temp.root.toPath().resolve("outside"))
    }

    @Test
    fun validate() {
        assumeTrue("symbolic links are not available", files.symlinks || !case.needsSymlinks)
        val result = StoryValidator(RootStoryFiles(files.root)).validate(Json.parseToJsonElement(case.json))
        val errors = (result as? ValidationResult.Invalid)?.errors.orEmpty()
        assertEquals(case.expected.map { (step, code) -> step to code.code }, errors.map { it.step to it.code })
        assertEquals(case.expected.isEmpty(), result is ValidationResult.Valid)
        for (error in errors) {
            assertTrue("message of $error", error.message.isNotBlank())
            assertTrue("hint of $error", error.hint.isNotBlank())
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<ValidationCase> = ValidationCases.all
    }
}

class StoryValidatorPathTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun savedPath(files: StoryFiles?, path: String): String {
        val json = Json.parseToJsonElement(story(step(file = quoted(path))))
        val result = StoryValidator(files).validate(json) as ValidationResult.Valid
        return result.story.steps.single().location!!.file
    }

    private fun fixture() = FixtureFiles(temp.newFolder("repo").toPath(), temp.root.toPath().resolve("outside"))

    @Test
    fun `saves the path from the repository root with slashes`() {
        val files = RootStoryFiles(fixture().root)
        assertEquals("src/App.kt", savedPath(files, "./src//App.kt"))
        assertEquals("src/App.kt", savedPath(files, "src\\App.kt"))
        assertEquals("src/App.kt", savedPath(null, "./src/App.kt"))
    }

    @Test
    fun `saves the real path of a link inside the project`() {
        val fixture = fixture()
        assumeTrue("symbolic links are not available", fixture.symlinks)
        assertEquals("src/App.kt", savedPath(RootStoryFiles(fixture.root), "inside-link.kt"))
    }
}

class StoryValidatorAnchorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val fixture by lazy { FixtureFiles(temp.newFolder("repo").toPath(), temp.root.toPath().resolve("outside")) }

    private fun validate(json: String, files: StoryFiles? = null): ValidationResult =
        StoryValidator(files ?: RootStoryFiles(fixture.root)).validate(Json.parseToJsonElement(json))

    @Test
    fun `a nearby anchor moves the range, stores its code and reports a correction`() {
        val result = validate(story(
            step(),
            step(start = "5", end = "7", anchor = quoted("line 2")),
            step(start = "9", end = "9", anchor = quoted("  line 6")),
        )) as ValidationResult.Valid

        assertEquals(
            listOf(
                Location("src/App.kt", 2, 4, "line 2", "line 2\nline 3\nline 4"),
                Location("src/App.kt", 2, 4, "line 2", "line 2\nline 3\nline 4"),
                Location("src/App.kt", 6, 6, "line 6", "line 6"),
            ),
            result.story.steps.map { it.location },
        )
        assertEquals(listOf(2, 3), result.corrections.map { it.step })
        assertEquals(setOf(CorrectionCode.LINES_SHIFTED.code), result.corrections.map { it.code }.toSet())
        assertEquals("Step 2: lines 5–7 changed to 2–4.", result.corrections[0].message)
        assertEquals("Step 3: line 9 changed to 6.", result.corrections[1].message)
        assertTrue(result.corrections.all { it.hint.contains("line ") })
    }

    @Test
    fun `stored code keeps indentation and has LF line breaks`() {
        val result = validate(story(
            step(file = quoted("src/Windows.kt"), start = "1", end = "3", anchor = quoted("one")),
            step(file = quoted("src/Twice.kt"), start = "1", end = "3", anchor = quoted("fun a() {")),
        )) as ValidationResult.Valid
        assertEquals(listOf("one\ntwo\nthree", "fun a() {\n    return\n}"), result.story.steps.map { it.location!!.rangeText })
        assertEquals(emptyList<ReportItem>(), result.corrections)
    }

    @Test
    fun `the anchor check reads the text that the files provider gives`() {
        val edited = object : StoryFiles {
            override val rootLabel = "repo"
            override fun lookup(path: String) = FileLookup.Found(path, "new line\n" + (1..10).joinToString("\n") { "line $it" })
        }
        val result = validate(story(step()), edited) as ValidationResult.Valid
        assertEquals(3 to 5, result.story.steps.single().location!!.let { it.startLine to it.endLine })
        assertEquals("Step 1: lines 2–4 changed to 3–5.", result.corrections.single().message)
    }

    @Test
    fun `a rejected story still reports the corrections of its other steps`() {
        val result = validate(story(step(start = "5", end = "7", anchor = quoted("line 2")), step(file = quoted("src/Missing.kt")))) as ValidationResult.Invalid
        assertEquals(listOf(2 to ErrorCode.FILE_NOT_FOUND.code), result.errors.map { it.step to it.code })
        assertEquals(listOf(1), result.corrections.map { it.step })
    }

    @Test
    fun `a shifted end beyond the largest line number is rejected without overflowing`() {
        val result = validate(story(
            step(start = "1", end = Int.MAX_VALUE.toString(), anchor = quoted("line 2")),
            step(start = "5", end = "7", anchor = quoted("line 2")),
        )) as ValidationResult.Invalid

        val error = result.errors.single()
        assertEquals(1 to ErrorCode.LINES_OUTSIDE_FILE.code, error.step to error.code)
        assertTrue(error.message, error.message.contains("the range ends at line 2147483648"))
        assertEquals(listOf(2), result.corrections.map { it.step })
    }

    @Test
    fun `anchor errors show the line that the anchor was compared with`() {
        val notFound = (validate(story(step(anchor = quoted("fun missing()")))) as ValidationResult.Invalid).errors.single()
        assertTrue(notFound.message, notFound.message.contains("line 2 of `src/App.kt` or within 20 lines of it. Line 2 is `line 2`."))
        val empty = (validate(story(step(file = quoted("src/Twice.kt"), start = "4", end = "4", anchor = quoted("missing")))) as ValidationResult.Invalid).errors.single()
        assertTrue(empty.message, empty.message.endsWith("Line 4 is empty."))
        val ambiguous = (validate(story(step(file = quoted("src/Twice.kt"), start = "4", end = "4", anchor = quoted("return")))) as ValidationResult.Invalid).errors.single()
        assertTrue(ambiguous.message, ambiguous.message.contains("It is at lines 2 and 6, at the same distance."))
    }

    @Test
    fun `kinds default to explanation and proposed code keeps its indentation with LF line breaks`() {
        val result = validate(story(
            step(),
            step(kind = quoted("context"), location = null),
            step(kind = quoted("proposal"), proposedCode = jsonText("    first()\r\n\r\n    second()\r\n")),
            step(kind = quoted("proposal"), location = null, proposedCode = jsonText("fun retry() {\n}\n\n")),
        )) as ValidationResult.Valid
        assertEquals(listOf(StepKind.EXPLANATION, StepKind.CONTEXT, StepKind.PROPOSAL, StepKind.PROPOSAL), result.story.steps.map { it.kind })
        assertEquals(listOf(null, null, "    first()\n\n    second()", "fun retry() {\n}\n"), result.story.steps.map { it.proposedCode })
        assertEquals("line 2\nline 3\nline 4", result.story.steps[2].location!!.rangeText)
    }

    @Test
    fun `an agent cannot set the stored code and must send an anchor`() {
        val withCode = """{"title": "T", "comment": "C.", "location": {"file": "src/App.kt", "startLine": 2, "endLine": 3, "anchorText": "line 2", "rangeText": "old\ncode"}}"""
        val withoutAnchor = """{"title": "T", "comment": "C.", "location": {"file": "src/App.kt", "startLine": 2, "endLine": 3}}"""
        val structure = StoryValidator(null).validate(Json.parseToJsonElement(story(withCode))) as ValidationResult.Valid
        assertEquals(Location("src/App.kt", 2, 3, "line 2", null), structure.story.steps.single().location)
        val checked = validate(story(withCode)) as ValidationResult.Valid
        assertEquals("line 2\nline 3", checked.story.steps.single().location!!.rangeText)
        val rejected = StoryValidator(null).validate(Json.parseToJsonElement(story(withoutAnchor))) as ValidationResult.Invalid
        assertEquals(listOf(1 to ErrorCode.MISSING_FIELD.code), rejected.errors.map { it.step to it.code })
    }
}
