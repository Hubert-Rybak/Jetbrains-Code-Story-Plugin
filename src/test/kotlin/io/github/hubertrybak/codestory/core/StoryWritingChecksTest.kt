package io.github.hubertrybak.codestory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

class WritingCase(
    private val name: String,
    val field: String,
    val text: String,
    vararg val expected: WarningCode,
    val hints: Boolean = false,
) {
    override fun toString() = name
}

object WritingCases {
    private fun sentence(first: String, words: Int) = first + " word".repeat(words - 1) + "."

    val all = listOf(
        WritingCase("eight title words", "title", "one two three four five six seven eight"),
        WritingCase("nine title words", "title", "one two three four five six seven eight nine", WarningCode.TITLE_TOO_LONG),
        WritingCase("step title length", "step-title", "one two three four five six seven eight nine", WarningCode.TITLE_TOO_LONG),
        WritingCase("code span is one title word", "title", "Read `OrderService.retry(one, two, three, four)` with care"),
        WritingCase("one summary sentence", "summary", "The service charges the card."),
        WritingCase("three summary sentences", "summary", "One. Two. Three."),
        WritingCase("four summary sentences", "summary", "One. Two. Three. Four.", WarningCode.SUMMARY_SENTENCES),
        WritingCase("summary without words", "summary", "...", WarningCode.SUMMARY_SENTENCES),
        WritingCase("one comment sentence", "comment", "The service charges the card."),
        WritingCase("three comment sentences", "comment", "One. Two. Three."),
        WritingCase("four comment sentences", "comment", "One. Two. Three. Four.", WarningCode.COMMENT_SENTENCES),
        WritingCase("comment without words", "comment", "...", WarningCode.COMMENT_SENTENCES),
        WritingCase("four bullet items", "comment", "Check these cases.\n- Valid request\n* Empty request\n+ No card\n- Provider failure"),
        WritingCase("five bullet items", "comment", "Check these cases.\n- One\n- Two\n- Three\n- Four\n- Five", WarningCode.COMMENT_LIST),
        WritingCase("list without lead", "comment", "- One\n- Two", WarningCode.COMMENT_LIST),
        WritingCase("list with two lead sentences", "comment", "Check these cases. Use each input.\n- One", WarningCode.COMMENT_LIST),
        WritingCase("empty list item", "comment", "Check these cases.\n- ", WarningCode.COMMENT_LIST),
        WritingCase("paragraph after list", "comment", "Check these cases.\n- One\nMore text.", WarningCode.COMMENT_LIST),
        WritingCase("wrapped list item", "comment", "Check these cases.\n- A valid request\n  with a card\n- A missing card"),
        WritingCase("sixty comment words", "comment", List(3) { sentence("The", 20) }.joinToString(" ")),
        WritingCase("sixty-one comment words", "comment", listOf(sentence("The", 20), sentence("The", 20), sentence("The", 21)).joinToString(" "), WarningCode.COMMENT_TOO_LONG),
        WritingCase("sixty words including list", "comment", "Check these cases.\n" + List(3) { "- " + List(19) { "word" }.joinToString(" ") }.joinToString("\n")),
        WritingCase("sixty-one words including list", "comment", "Check these cases.\n" + listOf(19, 19, 20).joinToString("\n") { "- " + List(it) { "word" }.joinToString(" ") }, WarningCode.COMMENT_TOO_LONG),
        WritingCase("twenty-word instruction", "comment", sentence("Check", 20)),
        WritingCase("twenty-one-word instruction", "comment", sentence("Check", 21), WarningCode.SENTENCE_TOO_LONG),
        WritingCase("instruction after prefix", "comment", "Please " + sentence("Check", 20), WarningCode.SENTENCE_TOO_LONG),
        WritingCase("instruction addressed to reader", "comment", "You must " + sentence("check", 19), WarningCode.SENTENCE_TOO_LONG),
        WritingCase("twenty-five-word description", "comment", sentence("The", 25)),
        WritingCase("twenty-six-word description", "comment", sentence("The", 26), WarningCode.SENTENCE_TOO_LONG),
        WritingCase("summary sentence length", "summary", sentence("The", 26), WarningCode.SENTENCE_TOO_LONG),
        WritingCase("bullet sentence length", "comment", "Check this case.\n- " + sentence("Check", 21), WarningCode.SENTENCE_TOO_LONG),
        WritingCase("abbreviations are not extra sentences", "summary", "Use e.g. `Payment.Id` values. Check version 1.2. Continue."),
        WritingCase("dotted code names are one word", "comment", "Check " + List(19) { "OrderService.retry" }.joinToString(" ") + "."),
        WritingCase("code punctuation is not prose", "comment", "Check `call(one; two, \"don't\")` and `a.b()` values."),
        WritingCase("links count visible labels only", "comment", "Check **the result** in [the guide](https://example.com/very/long/path;value?q=test)."),
        WritingCase("semicolon in comment", "comment", "The service checks the card; it then charges it.", WarningCode.SEMICOLON),
        WritingCase("semicolon in title", "title", "Read; check", WarningCode.SEMICOLON),
        WritingCase("semicolon in summary", "summary", "The service checks; it charges.", WarningCode.SEMICOLON),
        *listOf("don't", "can't", "won't", "isn't", "we're", "you've", "they'll", "I'd", "I'm", "it's", "that's", "let's", "doesn’t", "That’s").map {
            WritingCase("contraction $it", "comment", "Check whether $it valid.", WarningCode.CONTRACTION)
        }.toTypedArray(),
        WritingCase("possessives are not contractions", "comment", "The service's result contains the reader's request and James's ID."),
        WritingCase("full words are not contractions", "comment", "The service does not change the request."),
        WritingCase("hints off by default", "comment", "The value is stored while using a mapping."),
        WritingCase("optional hints", "comment", "The value is stored while using a mapping.", WarningCode.PASSIVE_VOICE, WarningCode.ING_WORD, hints = true),
        WritingCase("irregular passive hint", "comment", "The request is sent to the service.", WarningCode.PASSIVE_VOICE, hints = true),
        WritingCase("ing hint", "comment", "The worker keeps reading the value.", WarningCode.ING_WORD, hints = true),
        WritingCase("string and mapping are nouns", "comment", "The string contains a mapping.", hints = true),
        WritingCase("dotted technical names are not ing verbs", "comment", "The service uses java.lang.String and Data.mapping.", hints = true),
        WritingCase("adjectives are not passive voice", "comment", "The pane is open. The indicator is green.", hints = true),
        WritingCase("active voice and code names", "comment", "`Thing.isStored` saves `mapping` as a string.", hints = true),
        WritingCase("thirty-line range", "range", "39"),
        WritingCase("thirty-one-line range", "range", "40", WarningCode.RANGE_TOO_LONG),
    )
}

@RunWith(Parameterized::class)
class StoryWritingChecksTest(private val case: WritingCase) {
    @Test
    fun `checks writing without rejecting or rewriting the story`() {
        val step = Step("Entry point", "The app starts here.", Location("src/App.kt", 10, 12))
        val base = Story("Payment flow", StoryType.CHANGE, "The service charges the card.",
            "The developer's original request; don't change it.", StoryAuthor("Test Agent", "test-model"), listOf(step))
        val input = when (case.field) {
            "title" -> base.copy(title = case.text)
            "step-title" -> base.copy(steps = listOf(step.copy(title = case.text)))
            "summary" -> base.copy(summary = case.text)
            "comment" -> base.copy(steps = listOf(step.copy(comment = case.text)))
            "range" -> base.copy(steps = listOf(step.copy(location = Location("src/App.kt", 10, case.text.toInt()))))
            else -> error(case.field)
        }
        val warnings = StoryWritingChecks(case.hints).check(input)
        assertEquals(case.toString(), case.expected.map { it.code }.toSet(), warnings.map { it.code }.toSet())
        assertTrue(warnings.all { it.step == if (case.field in setOf("title", "summary")) null else 1 })
        assertTrue(warnings.all { it.message.isNotBlank() && it.hint.isNotBlank() })
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): Collection<Array<Any>> = WritingCases.all.map { arrayOf(it) }
    }
}
