package io.github.hubertrybak.codestory.core

import java.util.Locale

enum class WarningCode(val code: String) {
    TITLE_TOO_LONG("title-too-long"),
    SUMMARY_SENTENCES("summary-sentences"),
    COMMENT_SENTENCES("comment-sentences"),
    COMMENT_LIST("comment-list"),
    COMMENT_TOO_LONG("comment-too-long"),
    SENTENCE_TOO_LONG("sentence-too-long"),
    SEMICOLON("semicolon"),
    CONTRACTION("contraction"),
    PASSIVE_VOICE("passive-voice"),
    ING_WORD("ing-word"),
    RANGE_TOO_LONG("range-too-long"),
    UNKNOWN_BASE_REF("unknown-base-ref"),
}

/** Mechanical writing advice, in our own words. No dictionary or IDE dependencies. */
class StoryWritingChecks(private val optionalStyleHints: Boolean = false) {
    /** The text that a warning is about: [owner] names it in the message, and [step] is its 1-based step. */
    private class Field(val step: Int?, val name: String, val owner: String = if (step == null) "The story" else "Step $step")

    /** How long a comment or a reply can be. */
    private class Limits(val sentences: IntRange, val items: Int, val words: Int, val noun: String)

    fun check(story: Story): List<ReportItem> = buildList {
        title(story.title, Field(null, "title"), this)
        val summary = plainText(story.summary)
        val count = sentences(summary).size
        val summaryField = Field(null, "summary")
        if (count !in 1..3) warning(this, summaryField, WarningCode.SUMMARY_SENTENCES,
            "has $count sentences", "Use 1 to 3 sentences in the summary.")
        prose(summary, summaryField, this)
        story.steps.forEachIndexed { index, step ->
            val number = index + 1
            title(step.title, Field(number, "title"), this)
            comment(step.comment, Field(number, "comment"), COMMENT, this)
            step.location?.let { location ->
                val lines = location.endLine.toLong() - location.startLine + 1
                if (lines > 30) warning(this, Field(number, "location"), WarningCode.RANGE_TOO_LONG,
                    "covers $lines lines", "Point at the key lines only, usually 30 lines or fewer.")
            }
        }
    }

    /** Advice for an agent's reply in a comment thread. Answers can be longer than step comments; a reason to decline has 1 to 3 sentences. */
    fun reply(text: String, outcome: ReplyOutcome): List<ReportItem> = buildList {
        comment(text, Field(null, "text", "The reply"), if (outcome == ReplyOutcome.DECLINED) REASON else ANSWER, this)
    }

    private fun title(text: String, field: Field, warnings: MutableList<ReportItem>) {
        val plain = plainText(text)
        val count = words(plain).size
        if (count > 8) warning(warnings, field, WarningCode.TITLE_TOO_LONG,
            "has $count words", "Shorten the title to 8 words or fewer.")
        style(plain, field, warnings)
    }

    private fun comment(text: String, field: Field, limits: Limits, warnings: MutableList<ReportItem>) {
        val plain = plainText(text)
        val count = words(plain).size
        if (count > limits.words) warning(warnings, field, WarningCode.COMMENT_TOO_LONG,
            "has $count words", "Keep the whole ${limits.noun}, including list items, to ${limits.words} words or fewer.")
        val lines = plain.lines()
        val firstItem = lines.indexOfFirst { bullet.matches(it) }
        if (firstItem < 0) {
            val sentenceCount = sentences(plain).size
            if (sentenceCount !in limits.sentences) warning(warnings, field, WarningCode.COMMENT_SENTENCES,
                "has $sentenceCount sentences",
                "Use ${limits.sentences.first} to ${limits.sentences.last} sentences, or one lead sentence and up to ${limits.items} list items.")
            prose(plain, field, warnings)
        } else {
            val lead = lines.take(firstItem).joinToString(" ")
            val items = mutableListOf<String>()
            var invalid = false
            for (line in lines.drop(firstItem)) {
                val match = bullet.matchEntire(line)
                when {
                    match != null -> items += match.groupValues[1]
                    line.isBlank() -> Unit
                    line.first().isWhitespace() -> items[items.lastIndex] += " " + line.trim()
                    else -> { invalid = true; items[items.lastIndex] += " " + line }
                }
            }
            if (sentences(lead).size != 1 || items.size > limits.items || items.any { words(it).isEmpty() } || invalid) {
                warning(warnings, field, WarningCode.COMMENT_LIST,
                    "does not have one lead sentence and 1 to ${limits.items} list items",
                    "Use one lead sentence followed by up to ${limits.items} nonempty bullet items, with no extra paragraphs.")
            }
            sentenceLengths(lead, field, warnings)
            items.forEach { sentenceLengths(it, field, warnings) }
            style(plain, field, warnings)
        }
    }

    private fun prose(text: String, field: Field, warnings: MutableList<ReportItem>) {
        sentenceLengths(text, field, warnings)
        style(text, field, warnings)
    }

    private fun sentenceLengths(text: String, field: Field, warnings: MutableList<ReportItem>) {
        for (sentence in sentences(text)) {
            val tokens = words(sentence)
            val instruction = isInstruction(tokens)
            val limit = if (instruction) 20 else 25
            if (tokens.size > limit) warning(warnings, field, WarningCode.SENTENCE_TOO_LONG,
                "has a ${tokens.size}-word ${if (instruction) "instruction" else "description"}",
                "Split the sentence. Use at most 20 words for an instruction and 25 for a description.")
        }
    }

    private fun style(text: String, field: Field, warnings: MutableList<ReportItem>) {
        if (';' in text) warning(warnings, field, WarningCode.SEMICOLON,
            "contains a semicolon", "Use a full stop, or split the sentence.")
        if (contraction.containsMatchIn(text)) warning(warnings, field, WarningCode.CONTRACTION,
            "contains a contraction", "Write the words in full, for example, use 'does not' instead of 'doesn't'.")
        if (!optionalStyleHints) return
        if (passive.containsMatchIn(text)) warning(warnings, field, WarningCode.PASSIVE_VOICE,
            "may use passive voice", "Consider naming the actor and using active voice. This is an optional hint.")
        if (words(text).any { it.lowercase(Locale.ROOT).let { word -> word.length > 4 && word.all { it.isLetter() } && word.endsWith("ing") && word !in ingNouns } }) {
            warning(warnings, field, WarningCode.ING_WORD,
                "may use an -ing verb", "Consider a direct verb. Keep technical nouns when they are needed. This is an optional hint.")
        }
    }

    private fun warning(warnings: MutableList<ReportItem>, field: Field, code: WarningCode, problem: String, hint: String) {
        warnings += ReportItem(field.step, code.code, "${field.owner} `${field.name}` $problem.", hint)
    }

    private fun words(text: String) = word.findAll(text).map { it.value }.toList()

    private fun sentences(text: String): List<String> {
        // Protect familiar abbreviations. Dotted names and decimals already stay intact because
        // a sentence boundary must be followed by whitespace or the end of the text.
        val protected = abbreviation.replace(text) { it.value.replace(".", "") }
        return sentenceEnd.split(protected).map { it.trim() }.filter { words(it).isNotEmpty() }
    }

    private fun isInstruction(tokens: List<String>): Boolean {
        val lower = tokens.map { it.lowercase(Locale.ROOT) }.dropWhile { it in instructionPrefixes }
        return lower.firstOrNull() in imperativeVerbs ||
            (lower.firstOrNull() == "you" && lower.getOrNull(1) in setOf("must", "should", "need"))
    }

    private fun plainText(text: String): String {
        // Code spans (including spaces, punctuation and contractions) count as one word.
        // Link targets are not visible prose; only their labels participate in the checks.
        val labels = link.replace(text) { it.groupValues[1] }
        return code.replace(labels, "CodeName").replace("**", "").replace('’', '\'')
    }

    companion object {
        private val COMMENT = Limits(1..3, 4, 60, "comment")
        private val ANSWER = Limits(1..5, 6, 100, "reply")
        private val REASON = Limits(1..3, 6, 100, "reply")
        private val code = Regex("`[^`\\n]+`")
        private val link = Regex("\\[([^]\\n]+)]\\([^\\n)]+\\)")
        private val word = Regex("[\\p{L}\\p{N}_]+(?:[.'’:/#-][\\p{L}\\p{N}_]+)*")
        private val bullet = Regex("^\\s*[-*+]\\s+(.*)$")
        private val sentenceEnd = Regex("[.!?]+[\\\"'”’)]*(?=\\s|$)")
        private val abbreviation = Regex("\\b(?:e\\.g\\.|i\\.e\\.|etc\\.|vs\\.|Mr\\.|Mrs\\.|Ms\\.|Dr\\.)", RegexOption.IGNORE_CASE)
        private val contraction = Regex("\\b\\p{L}+(?:n't|'(?:re|ve|ll|d|m))\\b|\\b(?:it|that|there|here|what|who|where|when|why|how|he|she|this|let)'s\\b", RegexOption.IGNORE_CASE)
        private val passive = Regex("\\b(?:am|is|are|was|were|be|been|being)\\s+(?:\\p{L}+ly\\s+)*(?:\\p{L}{2,}ed|born|broken|built|brought|caught|chosen|done|driven|eaten|found|forgotten|frozen|given|held|kept|known|made|read|run|seen|sent|set|shown|spoken|stolen|taken|told|worn|written)\\b", RegexOption.IGNORE_CASE)
        private val ingNouns = setOf("string", "mapping", "thing", "anything", "everything", "nothing", "something", "spring", "sibling", "ceiling", "billing", "logging", "caching", "encoding", "decoding", "binding", "routing", "setting", "settings", "naming")
        private val instructionPrefixes = setOf("please", "then", "next", "first", "finally")
        private val imperativeVerbs = setOf("add", "apply", "avoid", "build", "call", "change", "check", "choose", "click", "close", "compare", "confirm", "continue", "copy", "create", "delete", "disable", "do", "enable", "ensure", "enter", "execute", "find", "finish", "follow", "give", "go", "inspect", "install", "keep", "load", "make", "move", "navigate", "note", "open", "pass", "point", "press", "process", "read", "refresh", "remove", "replace", "restart", "retry", "return", "review", "run", "save", "select", "send", "set", "show", "skip", "split", "start", "stop", "test", "turn", "update", "use", "validate", "verify", "view", "wait", "write")
    }
}
