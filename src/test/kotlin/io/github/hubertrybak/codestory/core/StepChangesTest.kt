package io.github.hubertrybak.codestory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepChangesTest {
    /** Lines 5–8 (1-based) are current lines 4–7 (0-based). */
    private fun change(vararg changes: LineChange) = StepChanges.of(5, 8, changes.toList())

    @Test
    fun `a range of new lines is added`() {
        assertEquals(StepChange.ADDED, change(LineChange(4, 4, 4, 8)))
        assertEquals(StepChange.ADDED, change(LineChange(2, 2, 2, 10)))
        // Git shows changed lines as removed and added, so a range of changed lines is also new.
        assertEquals(StepChange.ADDED, change(LineChange(4, 6, 4, 8)))
        assertEquals(StepChange.ADDED, change(LineChange(4, 4, 4, 6), LineChange(6, 7, 6, 8)))
        // A file that is new since the base.
        assertEquals(StepChange.ADDED, StepChanges.of(1, 3, listOf(LineChange(0, 1, 0, 3))))
    }

    @Test
    fun `a range with some new or changed lines is modified`() {
        assertEquals(StepChange.MODIFIED, change(LineChange(5, 6, 5, 6)))
        assertEquals(StepChange.MODIFIED, change(LineChange(0, 0, 0, 5)))
        assertEquals(StepChange.MODIFIED, change(LineChange(7, 7, 7, 12)))
        assertEquals(StepChange.MODIFIED, change(LineChange(4, 4, 4, 7)))
    }

    @Test
    fun `lines removed inside the range modify it, lines removed at its edges do not`() {
        assertEquals(StepChange.MODIFIED, change(LineChange(5, 9, 5, 5)))
        assertEquals(StepChange.MODIFIED, change(LineChange(7, 8, 7, 7)))
        assertNull(change(LineChange(4, 6, 4, 4)))
        assertNull(change(LineChange(8, 9, 8, 8)))
    }

    @Test
    fun `a range without changed lines has no change`() {
        assertNull(change())
        assertNull(change(LineChange(0, 1, 0, 4), LineChange(10, 10, 8, 20)))
    }

    @Test
    fun `only revisions that Git cannot read as options or paths are used`() {
        for (revision in listOf("HEAD", "0123abcd", "0123456789abcdef0123456789abcdef01234567", "main", "origin/main", "HEAD~2", "v1.0^", "feature/a-b_c")) {
            assertTrue(revision, StepChanges.isRevision(revision))
        }
        for (revision in listOf("", "--output=/tmp/x", "-p", "HEAD:secret.txt", "HEAD@{1}", "a b", "main\n--all", ".hidden", "x".repeat(201))) {
            assertFalse(revision, StepChanges.isRevision(revision))
        }
    }
}
