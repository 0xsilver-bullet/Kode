package com.silverbullet.kode.feature.threads.domain.review

/**
 * A single-pass unified-diff parser.
 *
 * Hand-written because the server sends diffs as *text* (`review.getDiffPreview`,
 * `orchestration.getTurnDiff`) and `kotlin-multiplatform-diff` deliberately
 * ships no unified-diff support — that library is used one level up, for
 * word-level intra-line highlights only.
 *
 * Performance notes: one `split('\n')` and one forward scan, no regex on the
 * hot per-line path (hunk lines are classified by their first character), and
 * hunk headers are parsed with a hand-rolled cursor. A 120 KB patch — the
 * server's cap — parses in well under a frame.
 */

enum class DiffChangeType { Modified, New, Deleted, RenamePure, RenameChanged }

enum class DiffLineKind { Context, Add, Delete }

class DiffLine(
    val kind: DiffLineKind,
    val text: String,
    /** 1-based line number in the old file; null for additions. */
    val oldLine: Int?,
    /** 1-based line number in the new file; null for deletions. */
    val newLine: Int?,
)

class DiffHunk(
    val header: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>,
)

class DiffFile(
    val oldPath: String,
    val newPath: String,
    val changeType: DiffChangeType,
    val isBinary: Boolean,
    val hunks: List<DiffHunk>,
    val additions: Int,
    val deletions: Int,
    /** The longest line's length, for sizing the horizontal pan surface. */
    val maxLineLength: Int,
) {
    val displayPath: String get() = if (newPath != DEV_NULL && newPath.isNotEmpty()) newPath else oldPath

    private companion object {
        const val DEV_NULL = "/dev/null"
    }
}

class ParsedDiff(
    val files: List<DiffFile>,
    /** True when the input ended with the server's `[truncated]` cap marker. */
    val truncated: Boolean,
)

/** The literal suffix `GitVcsDriverCore` appends when a patch hits its byte cap. */
private const val TRUNCATION_MARKER = "[truncated]"

fun parseUnifiedDiff(patchText: String): ParsedDiff {
    var text = patchText
    var truncated = false
    val trimmedEnd = text.trimEnd()
    if (trimmedEnd.endsWith(TRUNCATION_MARKER)) {
        truncated = true
        text = trimmedEnd.removeSuffix(TRUNCATION_MARKER).trimEnd()
    }
    if (text.isBlank()) return ParsedDiff(emptyList(), truncated)

    val lines = text.split('\n')
    val files = ArrayList<DiffFile>()
    var index = 0

    while (index < lines.size) {
        if (!lines[index].startsWith("diff --git ")) {
            index++
            continue
        }
        index = parseFile(lines, index, files)
    }

    return ParsedDiff(files, truncated)
}

/** Parses one `diff --git …` section starting at [start]; returns the next index. */
private fun parseFile(lines: List<String>, start: Int, into: ArrayList<DiffFile>): Int {
    var index = start
    val gitPaths = parseGitHeaderPaths(lines[index])
    index++

    var oldPath = gitPaths?.first.orEmpty()
    var newPath = gitPaths?.second.orEmpty()
    var isNew = false
    var isDeleted = false
    var isRename = false
    var isBinary = false

    // Extended header lines, until the first hunk or the next file.
    while (index < lines.size) {
        val line = lines[index]
        when {
            line.startsWith("diff --git ") || line.startsWith("@@") -> break
            line.startsWith("new file mode") -> isNew = true
            line.startsWith("deleted file mode") -> isDeleted = true
            line.startsWith("rename from ") -> {
                isRename = true
                oldPath = line.removePrefix("rename from ").unquoteGitPath()
            }

            line.startsWith("rename to ") -> {
                isRename = true
                newPath = line.removePrefix("rename to ").unquoteGitPath()
            }

            line.startsWith("Binary files ") || line.startsWith("GIT binary patch") ->
                isBinary = true

            line.startsWith("--- ") ->
                line.substring(4).stripPathPrefix()?.let { oldPath = it }

            line.startsWith("+++ ") ->
                line.substring(4).stripPathPrefix()?.let { newPath = it }
        }
        index++
    }

    // Hunks.
    val hunks = ArrayList<DiffHunk>()
    var additions = 0
    var deletions = 0
    var maxLineLength = 0

    while (index < lines.size && lines[index].startsWith("@@")) {
        val headerLine = lines[index]
        val header = parseHunkHeader(headerLine) ?: break
        index++

        val hunkLines = ArrayList<DiffLine>()
        var oldLine = header.oldStart
        var newLine = header.newStart

        loop@ while (index < lines.size) {
            val raw = lines[index]
            if (raw.isEmpty()) {
                // A bare empty line inside a hunk is a context line whose
                // content is empty (git prints " " which some transports trim).
                // Context consumes both counters, so it is only plausible while
                // both sides still expect lines; otherwise it ends the hunk.
                if (oldLine < header.oldStart + header.oldCount &&
                    newLine < header.newStart + header.newCount
                ) {
                    hunkLines.add(DiffLine(DiffLineKind.Context, "", oldLine, newLine))
                    oldLine++
                    newLine++
                    index++
                    continue@loop
                }
                break@loop
            }
            when (raw[0]) {
                ' ' -> {
                    val text = raw.substring(1)
                    if (text.length > maxLineLength) maxLineLength = text.length
                    hunkLines.add(DiffLine(DiffLineKind.Context, text, oldLine, newLine))
                    oldLine++
                    newLine++
                }

                '+' -> {
                    val text = raw.substring(1)
                    if (text.length > maxLineLength) maxLineLength = text.length
                    hunkLines.add(DiffLine(DiffLineKind.Add, text, null, newLine))
                    newLine++
                    additions++
                }

                '-' -> {
                    val text = raw.substring(1)
                    if (text.length > maxLineLength) maxLineLength = text.length
                    hunkLines.add(DiffLine(DiffLineKind.Delete, text, oldLine, null))
                    oldLine++
                    deletions++
                }

                // "\ No newline at end of file" — metadata, not content.
                '\\' -> Unit

                else -> break@loop
            }
            index++
        }

        hunks.add(
            DiffHunk(
                header = headerLine,
                oldStart = header.oldStart,
                oldCount = header.oldCount,
                newStart = header.newStart,
                newCount = header.newCount,
                lines = hunkLines,
            ),
        )
    }

    val changeType = when {
        isNew -> DiffChangeType.New
        isDeleted -> DiffChangeType.Deleted
        isRename && hunks.isEmpty() -> DiffChangeType.RenamePure
        isRename -> DiffChangeType.RenameChanged
        else -> DiffChangeType.Modified
    }

    into.add(
        DiffFile(
            oldPath = oldPath,
            newPath = newPath,
            changeType = changeType,
            isBinary = isBinary,
            hunks = hunks,
            additions = additions,
            deletions = deletions,
            maxLineLength = maxLineLength,
        ),
    )
    return index
}

private class HunkHeader(val oldStart: Int, val oldCount: Int, val newStart: Int, val newCount: Int)

/** `@@ -oldStart[,oldCount] +newStart[,newCount] @@ …`, parsed with a cursor. */
private fun parseHunkHeader(line: String): HunkHeader? {
    var cursor = 2 // past "@@"
    fun skipSpaces() {
        while (cursor < line.length && line[cursor] == ' ') cursor++
    }

    fun readInt(): Int? {
        val begin = cursor
        while (cursor < line.length && line[cursor].isDigit()) cursor++
        if (cursor == begin) return null
        return line.substring(begin, cursor).toIntOrNull()
    }

    skipSpaces()
    if (cursor >= line.length || line[cursor] != '-') return null
    cursor++
    val oldStart = readInt() ?: return null
    var oldCount = 1
    if (cursor < line.length && line[cursor] == ',') {
        cursor++
        oldCount = readInt() ?: return null
    }
    skipSpaces()
    if (cursor >= line.length || line[cursor] != '+') return null
    cursor++
    val newStart = readInt() ?: return null
    var newCount = 1
    if (cursor < line.length && line[cursor] == ',') {
        cursor++
        newCount = readInt() ?: return null
    }
    return HunkHeader(oldStart, oldCount, newStart, newCount)
}

/** Extracts both paths from `diff --git a/X b/Y`, tolerating quoted paths. */
private fun parseGitHeaderPaths(line: String): Pair<String, String>? {
    val rest = line.removePrefix("diff --git ").trim()
    if (rest.isEmpty()) return null
    val first: String
    val second: String
    if (rest.startsWith('"')) {
        val endOfFirst = rest.indexOf('"', startIndex = 1)
        if (endOfFirst < 0) return null
        first = rest.substring(1, endOfFirst)
        second = rest.substring(endOfFirst + 1).trim().unquoteGitPath()
    } else {
        // Paths with spaces but no quoting are ambiguous; splitting on
        // " b/" matches git's own convention.
        val split = rest.indexOf(" b/")
        if (split < 0) {
            val space = rest.indexOf(' ')
            if (space < 0) return null
            first = rest.substring(0, space)
            second = rest.substring(space + 1)
        } else {
            first = rest.substring(0, split)
            second = rest.substring(split + 1)
        }
    }
    return first.dropDiffPrefix() to second.dropDiffPrefix()
}

/** Strips the `a/`/`b/` prefix from a `---`/`+++` header path, or null to keep. */
private fun String.stripPathPrefix(): String? {
    val unquoted = trim().unquoteGitPath()
    return when {
        unquoted == "/dev/null" -> unquoted
        unquoted.length > 2 && (unquoted.startsWith("a/") || unquoted.startsWith("b/")) ->
            unquoted.substring(2)

        else -> null
    }
}

private fun String.dropDiffPrefix(): String = when {
    length > 2 && (startsWith("a/") || startsWith("b/")) -> substring(2)
    else -> this
}

private fun String.unquoteGitPath(): String {
    if (length < 2 || first() != '"' || last() != '"') return this
    // Git C-quotes unusual paths; unescape the common sequences.
    val inner = substring(1, length - 1)
    val builder = StringBuilder(inner.length)
    var index = 0
    while (index < inner.length) {
        val char = inner[index]
        if (char == '\\' && index + 1 < inner.length) {
            index++
            when (val escaped = inner[index]) {
                'n' -> builder.append('\n')
                't' -> builder.append('\t')
                '\\' -> builder.append('\\')
                '"' -> builder.append('"')
                else -> {
                    builder.append('\\')
                    builder.append(escaped)
                }
            }
        } else {
            builder.append(char)
        }
        index++
    }
    return builder.toString()
}
