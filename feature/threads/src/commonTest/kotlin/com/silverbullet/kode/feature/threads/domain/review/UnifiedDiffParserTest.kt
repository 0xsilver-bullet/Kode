package com.silverbullet.kode.feature.threads.domain.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnifiedDiffParserTest {

    @Test
    fun parsesModifiedFileWithLineNumbers() {
        val diff = """
            diff --git a/src/main.kt b/src/main.kt
            index 1111111..2222222 100644
            --- a/src/main.kt
            +++ b/src/main.kt
            @@ -10,4 +10,5 @@ fun main() {
                 unchanged1
            -    removed line
            +    added line
            +    another added
                 unchanged2
        """.trimIndent()

        val parsed = parseUnifiedDiff(diff)
        assertEquals(1, parsed.files.size)
        val file = parsed.files.single()
        assertEquals(DiffChangeType.Modified, file.changeType)
        assertEquals("src/main.kt", file.displayPath)
        assertEquals(2, file.additions)
        assertEquals(1, file.deletions)

        val lines = file.hunks.single().lines
        assertEquals(5, lines.size)
        // Context: both counters advance.
        assertEquals(10, lines[0].oldLine)
        assertEquals(10, lines[0].newLine)
        // Delete: old only.
        assertEquals(DiffLineKind.Delete, lines[1].kind)
        assertEquals(11, lines[1].oldLine)
        assertNull(lines[1].newLine)
        // Adds: new only, consecutive numbering.
        assertEquals(11, lines[2].newLine)
        assertEquals(12, lines[3].newLine)
        // Trailing context resumes both.
        assertEquals(12, lines[4].oldLine)
        assertEquals(13, lines[4].newLine)
    }

    @Test
    fun parsesNewAndDeletedFiles() {
        val diff = """
            diff --git a/new.txt b/new.txt
            new file mode 100644
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1,2 @@
            +hello
            +world
            diff --git a/gone.txt b/gone.txt
            deleted file mode 100644
            --- a/gone.txt
            +++ /dev/null
            @@ -1,1 +0,0 @@
            -bye
        """.trimIndent()

        val parsed = parseUnifiedDiff(diff)
        assertEquals(2, parsed.files.size)
        assertEquals(DiffChangeType.New, parsed.files[0].changeType)
        assertEquals("new.txt", parsed.files[0].displayPath)
        assertEquals(2, parsed.files[0].additions)
        assertEquals(DiffChangeType.Deleted, parsed.files[1].changeType)
        assertEquals("gone.txt", parsed.files[1].displayPath)
        assertEquals(1, parsed.files[1].deletions)
    }

    @Test
    fun parsesPureRenameAndBinary() {
        val diff = """
            diff --git a/old/name.kt b/new/name.kt
            similarity index 100%
            rename from old/name.kt
            rename to new/name.kt
            diff --git a/image.png b/image.png
            index 1111111..2222222 100644
            Binary files a/image.png and b/image.png differ
        """.trimIndent()

        val parsed = parseUnifiedDiff(diff)
        val rename = parsed.files[0]
        assertEquals(DiffChangeType.RenamePure, rename.changeType)
        assertEquals("old/name.kt", rename.oldPath)
        assertEquals("new/name.kt", rename.newPath)
        assertTrue(rename.hunks.isEmpty())

        val binary = parsed.files[1]
        assertTrue(binary.isBinary)
        assertTrue(binary.hunks.isEmpty())
    }

    @Test
    fun stripsTruncationMarker() {
        val diff = "diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n" +
            "@@ -1,1 +1,1 @@\n-x\n+y\n\n[truncated]"
        val parsed = parseUnifiedDiff(diff)
        assertTrue(parsed.truncated)
        assertEquals(1, parsed.files.size)
        assertEquals(1, parsed.files.single().additions)
    }

    @Test
    fun emptyInputParsesToNoFiles() {
        val parsed = parseUnifiedDiff("   \n  ")
        assertTrue(parsed.files.isEmpty())
        assertTrue(!parsed.truncated)
    }

    @Test
    fun tracksLongestLineForPanSizing() {
        val long = "x".repeat(120)
        val diff = "diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n" +
            "@@ -1,1 +1,1 @@\n-short\n+$long"
        assertEquals(120, parseUnifiedDiff(diff).files.single().maxLineLength)
    }
}
