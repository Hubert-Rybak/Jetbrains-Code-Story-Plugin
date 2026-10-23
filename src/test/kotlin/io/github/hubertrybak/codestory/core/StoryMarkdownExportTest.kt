package io.github.hubertrybak.codestory.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class StoryMarkdownExportTest {
    private fun story(vararg steps: Step) = Story(
        "Payment flow", StoryType.CHANGE, "The **controller** charges the card.", "Private original request",
        StoryAuthor("Agent", "Model"), steps.toList(), Instant.parse("2026-10-05T12:00:00Z"), StoryGit("branch", "commit"),
    )

    @Test fun `exports title summary ordered steps comments and relative line links`() {
        val result = StoryMarkdownExport.render(story(
            Step("Charge", "Call `charge()` once.\n- Save the result.\n- Return it.", Location("src/App.kt", 42, 57)),
            Step("Check", "Check the response.", Location("src/Check.kt", 9, 9)),
            Step("Overview", "The caller owns the retry."),
        ))
        assertEquals("""
            # Payment flow

            The **controller** charges the card.

            ## 1. Charge

            [src/App\.kt:L42-L57](src/App.kt#L42-L57)

            Call `charge()` once.
            - Save the result.
            - Return it.

            ## 2. Check

            [src/Check\.kt:L9-L9](src/Check.kt#L9-L9)

            Check the response.

            ## 3. Overview

            The caller owns the retry.
        """.trimIndent() + "\n", result)
        assertFalse(result.contains("Private original request"))
        assertFalse(result.contains("Agent"))
        assertFalse(result.contains("branch"))
    }

    @Test fun `escapes plain headings and labels and encodes unusual paths`() {
        val result = StoryMarkdownExport.render(story(
            Step("Use [x] *once*", "Keep **formatting** and [links](docs/readme.md).", Location("src/a (é)#1[ok].kt", 1, 2)),
        ).copy(title = "Title\n# injected <tag>"))
        assertTrue(result, result.startsWith("# Title \\# injected \\<tag\\>\n"))
        assertTrue(result, result.contains("## 1. Use \\[x\\] \\*once\\*"))
        assertTrue(result, result.contains("(src/a%20%28%C3%A9%29%231%5Bok%5D.kt#L1-L2)"))
        assertTrue(result, result.contains("Keep **formatting** and [links](docs/readme.md)."))
    }

    @Test fun `exports context as sections of their step and proposals with their code`() {
        val result = StoryMarkdownExport.render(story(
            Step("Charge", "Call `charge()` once.", Location("src/App.kt", 42, 43, "val result = charge()", "val result = charge()\nreturn result")),
            Step("The provider call", "The provider can time out.", Location("src/Provider.kt", 9, 9), kind = StepKind.CONTEXT),
            Step("Retry once", "A retry hides short failures.", Location("src/App.kt", 42, 43, "val result = charge()", "val result = charge()\nreturn result"),
                kind = StepKind.PROPOSAL, proposedCode = "val result = retry { charge() }\nreturn result"),
            Step("Add a test", "Cover the retry with ```fences```.", kind = StepKind.PROPOSAL, proposedCode = "@Test\nfun retries() = check(\"````\")"),
        ))
        assertEquals("""
            # Payment flow

            The **controller** charges the card.

            ## 1. Charge

            [src/App\.kt:L42-L43](src/App.kt#L42-L43)

            Call `charge()` once.

            ### 2. Context: The provider call

            [src/Provider\.kt:L9-L9](src/Provider.kt#L9-L9)

            The provider can time out.

            ## 3. Proposal: Retry once

            [src/App\.kt:L42-L43](src/App.kt#L42-L43)

            A retry hides short failures.

            ```diff
            -val result = charge()
            +val result = retry { charge() }
             return result
            ```

            ## 4. Proposal: Add a test

            Cover the retry with ```fences```.

            `````
            @Test
            fun retries() = check("````")
            `````
        """.trimIndent() + "\n", result)
    }

    @Test fun `comment threads in a saved file are never exported`() {
        val time = Instant.parse("2026-10-05T12:00:00Z")
        val thread = StoryThread("private", ThreadKind.QUESTION, ThreadState.SUBMITTED, listOf(ThreadMessage(MessageAuthor.READER, 1, time, "Private reviewer thread")))
        val step = Step("Overview", "The caller owns the retry.", id = "overview", threads = listOf(thread.copy(id = "on-step")))
        val withThreads = StoryFormat.encode(story(step).copy(threads = listOf(thread)), "2026-10-05-payment-flow")
        val decoded = StoryFormat.decode(withThreads) as StoryFormat.Decoded.Valid
        val result = StoryMarkdownExport.render(decoded.story)
        assertTrue(result.contains("The caller owns the retry."))
        assertFalse(result.contains("Private reviewer thread"))
    }
}
