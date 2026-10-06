package io.github.hubertrybak.codestory.core

import org.junit.Assert.*
import org.junit.Test

class StoryMarkdownTest {
    @Test fun `renders inline code bold and lists`() {
        assertEquals(
            "<p>Call <code>charge(&lt;id&gt;)</code> with <b>one payment</b>.</p><ul><li>Read <code>id</code>.</li><li><b>Save</b> it.</li></ul>",
            StoryMarkdown.render("Call `charge(<id>)` with **one payment**.\n- Read `id`.\n* **Save** it."),
        )
    }

    @Test fun `code and links cannot inject HTML`() {
        val html = StoryMarkdown.render("<script>alert(1)</script> `<img src=x>` [<b>link</b>](src/a\"b.kt)")
        assertFalse(html, html.contains("<script>"))
        assertFalse(html, html.contains("<img"))
        assertTrue(html, html.contains("<code>&lt;img src=x&gt;</code>"))
        assertTrue(html, html.contains("href=\"src/a&quot;b.kt\""))
        assertTrue(html, html.contains("&lt;b&gt;link&lt;/b&gt;"))
    }

    @Test fun `headings images tables and input HTML stay plain text`() {
        val html = StoryMarkdown.render("# Heading\n![image](https://example.com/picture.png)\n| a | b |\n<b>text</b>")
        assertFalse(html, html.contains("<h1"))
        assertFalse(html, html.contains("<img"))
        assertFalse(html, html.contains("<table"))
        assertFalse(html, html.contains("<a "))
        assertFalse(html, html.contains("<b>"))
        assertTrue(html, html.contains("&lt;b&gt;text&lt;/b&gt;"))
    }

    @Test fun `only http https and relative project files become links`() {
        for (target in listOf("https://example.com/a?q=1&b=2", "http://example.com", "src/App.kt", "src/App.kt#L2-L5")) {
            assertTrue(target, StoryMarkdown.render("[open]($target)").contains("<a "))
        }
        for (target in listOf("javascript:alert", "data:text/html,test", "file:///etc/passwd", "ftp://example.com", "//example.com", "/etc/passwd", "../outside.kt", "C:\\file.kt", "https://", "src/App.kt#L0", "src/App.kt#L5-L2", "src/App.kt#L9999999999999")) {
            assertFalse(target, StoryMarkdown.render("[open]($target)").contains("<a "))
        }
        assertEquals(StoryMarkdown.FileLink("src/App.kt", 2, 5), StoryMarkdown.fileLink("./src/App.kt#L2-L5"))
    }

    @Test fun `formatting inside code and unfinished syntax stays literal`() {
        assertEquals("<p><code>**text** [link](https://example.com)</code> **open `open [open</p>",
            StoryMarkdown.render("`**text** [link](https://example.com)` **open `open [open"))
        assertEquals("<p><b>Use <code>id</code></b></p>", StoryMarkdown.render("**Use `id`**"))
    }
}
