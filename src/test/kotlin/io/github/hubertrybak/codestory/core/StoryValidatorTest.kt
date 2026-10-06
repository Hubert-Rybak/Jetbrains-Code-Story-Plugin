package io.github.hubertrybak.codestory.core

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
        valid("CRLF file", story(step(file = quoted("src/Windows.kt"), start = "1", end = "3"))),
        valid("symbolic link inside the project", story(step(file = quoted("inside-link.kt"))), needsSymlinks = true),
        valid("text-only step", story(step(location = null))),
        valid("null location", story(step(location = "null"))),
        valid("stable step ID", story(step(id = quoted("entry")))),
        valid("reserved fields are ignored", story("""{"title": "Overview", "comment": "Text.", "children": [{}], "links": ["later"]}""")),
        *StoryType.entries.map { valid("type ${it.value}", story(step(), type = quoted(it.value))) }.toTypedArray(),

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
        invalid("no step title", story("""{"comment": "Text.", "location": {"file": "src/App.kt", "startLine": 1, "endLine": 2}}"""), 1 to ErrorCode.MISSING_FIELD),
        invalid("empty comment", story(step(comment = quoted(""))), 1 to ErrorCode.EMPTY_TEXT),
        invalid("comment is not text", story(step(comment = "[\"a\"]")), 1 to ErrorCode.WRONG_TYPE),
        invalid("location is not an object", story("""{"title": "Entry point", "comment": "Text.", "location": "src/App.kt:2"}"""), 1 to ErrorCode.WRONG_TYPE),
        invalid("multiple locations", story(step(location = """[{"file": "src/App.kt", "startLine": 1, "endLine": 2}]""")), 1 to ErrorCode.WRONG_TYPE),
        invalid("no file", story("""{"title": "T", "comment": "C.", "location": {"startLine": 1, "endLine": 2}}"""), 1 to ErrorCode.MISSING_FIELD),
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
        invalid("no start line", story("""{"title": "T", "comment": "C.", "location": {"file": "src/App.kt", "endLine": 2}}"""), 1 to ErrorCode.MISSING_FIELD),
        invalid("start line as text", story(step(start = "\"2\"")), 1 to ErrorCode.WRONG_TYPE),
        invalid("fractional start line", story(step(start = "2.5")), 1 to ErrorCode.WRONG_TYPE),
        invalid("start line 0", story(step(start = "0")), 1 to ErrorCode.INVALID_LINE),
        invalid("negative end line", story(step(start = "1", end = "-1")), 1 to ErrorCode.INVALID_LINE),
        invalid("end before start", story(step(start = "5", end = "3")), 1 to ErrorCode.REVERSED_RANGE),
        invalid("end after the last line", story(step(start = "8", end = "11")), 1 to ErrorCode.LINES_OUTSIDE_FILE),
        invalid("range after the last line", story(step(start = "40", end = "45")), 1 to ErrorCode.LINES_OUTSIDE_FILE),
        invalid("final line break is not a line", story(step(file = quoted("src/Windows.kt"), start = "4", end = "4")), 1 to ErrorCode.LINES_OUTSIDE_FILE),
        invalid("empty file", story(step(file = quoted("src/empty.txt"), start = "1", end = "1")), 1 to ErrorCode.LINES_OUTSIDE_FILE),
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
