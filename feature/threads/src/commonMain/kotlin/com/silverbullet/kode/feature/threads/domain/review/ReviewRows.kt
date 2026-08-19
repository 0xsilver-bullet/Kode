package com.silverbullet.kode.feature.threads.domain.review

import androidx.compose.runtime.Immutable

/**
 * The renderable row model the review list draws from — the Compose analogue of
 * t3code's `NativeReviewDiffRow` wire format. Rows are immutable, carry stable
 * ids, and are fully precomputed off the main thread (including word-diff
 * ranges), so binding a row in the lazy list does no work beyond building one
 * `AnnotatedString` for the visible line.
 */
@Immutable
sealed interface ReviewRow {
    val id: String
    val fileId: String

    @Immutable
    data class FileHeader(
        override val id: String,
        override val fileId: String,
        val path: String,
        /** The pre-rename path, when this file was renamed. */
        val previousPath: String?,
        val changeType: DiffChangeType,
        val additions: Int,
        val deletions: Int,
    ) : ReviewRow {
        // Draw-ready strings, built once at row construction (off the main
        // thread) so the canvas draw loop never formats or allocates.
        val additionsText: String = "+$additions"
        val deletionsText: String = "−$deletions"

        /** The second header line: rename origin or new/deleted marker. */
        val subtitle: String? = when {
            previousPath != null -> "Renamed from $previousPath"
            changeType == DiffChangeType.New -> "New file"
            changeType == DiffChangeType.Deleted -> "Deleted"
            else -> null
        }
    }

    @Immutable
    data class Hunk(
        override val id: String,
        override val fileId: String,
        val header: String,
    ) : ReviewRow

    @Immutable
    data class Line(
        override val id: String,
        override val fileId: String,
        val kind: DiffLineKind,
        val oldLine: Int?,
        val newLine: Int?,
        val text: String,
        /** Word-level highlight ranges within [text], empty when none apply. */
        val highlights: List<IntRange>,
    ) : ReviewRow {
        // Gutter strings, precomputed so drawing a visible line does zero
        // number formatting — the draw loop only does cache lookups.
        val oldLineText: String = oldLine?.toString().orEmpty()
        val newLineText: String = newLine?.toString().orEmpty()
    }

    /** Binary/rename/empty explanations, rendered where lines would be. */
    @Immutable
    data class Notice(
        override val id: String,
        override val fileId: String,
        val text: String,
    ) : ReviewRow
}

/** One file's precomputed block: its header plus its body rows. */
@Immutable
data class ReviewFileBlock(
    val header: ReviewRow.FileHeader,
    val body: List<ReviewRow>,
    val maxLineLength: Int,
)

@Immutable
data class ReviewRowData(
    val blocks: List<ReviewFileBlock>,
    val truncated: Boolean,
) {
    val isEmpty: Boolean get() = blocks.isEmpty()
}

/** File extensions never worth rendering as text, from t3code's `reviewModel.ts`. */
private val NON_TEXT_FILE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "icns", "avif", "heic", "tif", "tiff",
    "mp3", "wav", "flac", "ogg", "m4a", "aac", "mp4", "mov", "avi", "mkv", "webm", "pdf",
    "zip", "gz", "tgz", "bz2", "7z", "rar", "woff", "woff2", "ttf", "otf", "eot", "wasm",
    "exe", "dll", "so", "dylib",
)

/**
 * Flattens a parsed diff into renderable blocks, pairing delete-runs with the
 * add-run that follows them to compute word-level highlights (i-th delete
 * pairs with i-th add, the standard unified-diff pairing).
 */
fun buildReviewRows(parsed: ParsedDiff): ReviewRowData {
    val blocks = ArrayList<ReviewFileBlock>(parsed.files.size)

    parsed.files.forEachIndexed { fileIndex, file ->
        val fileId = "f$fileIndex:${file.displayPath}"
        val header = ReviewRow.FileHeader(
            id = fileId,
            fileId = fileId,
            path = file.displayPath,
            previousPath = file.oldPath
                .takeIf {
                    (file.changeType == DiffChangeType.RenamePure ||
                        file.changeType == DiffChangeType.RenameChanged) &&
                        it.isNotEmpty() && it != file.displayPath
                },
            changeType = file.changeType,
            additions = file.additions,
            deletions = file.deletions,
        )

        val body = ArrayList<ReviewRow>()
        if (file.hunks.isEmpty()) {
            noticeForEmptyFile(file)?.let { text ->
                body.add(ReviewRow.Notice(id = "$fileId:notice", fileId = fileId, text = text))
            }
        }

        file.hunks.forEachIndexed { hunkIndex, hunk ->
            body.add(
                ReviewRow.Hunk(
                    id = "$fileId:h$hunkIndex",
                    fileId = fileId,
                    header = hunk.header,
                ),
            )
            appendHunkLines(body, fileId, hunkIndex, hunk)
        }

        blocks.add(ReviewFileBlock(header = header, body = body, maxLineLength = file.maxLineLength))
    }

    return ReviewRowData(blocks = blocks, truncated = parsed.truncated)
}

private fun noticeForEmptyFile(file: DiffFile): String? {
    val extension = file.displayPath.substringAfterLast('.', "").lowercase()
    return when {
        file.isBinary || extension in NON_TEXT_FILE_EXTENSIONS ->
            "Unsupported format. Diff contents are not available."

        file.changeType == DiffChangeType.RenamePure ->
            "This file was renamed without modifications."

        else -> null
    }
}

/**
 * Emits a hunk's lines, wiring word-diff highlights across each paired
 * delete/add run. Runs are found in one pass; pairing work is bounded by the
 * word-diff caps, so a large hunk costs parsing only.
 */
private fun appendHunkLines(
    into: ArrayList<ReviewRow>,
    fileId: String,
    hunkIndex: Int,
    hunk: DiffHunk,
) {
    val lines = hunk.lines
    // Precomputed highlights, keyed by line index within the hunk.
    var highlightsByIndex: HashMap<Int, List<IntRange>>? = null

    var index = 0
    while (index < lines.size) {
        if (lines[index].kind == DiffLineKind.Delete) {
            val deleteStart = index
            while (index < lines.size && lines[index].kind == DiffLineKind.Delete) index++
            val addStart = index
            while (index < lines.size && lines[index].kind == DiffLineKind.Add) index++
            val deleteCount = addStart - deleteStart
            val addCount = index - addStart
            val paired = minOf(deleteCount, addCount)
            if (paired > 0) {
                val map = highlightsByIndex
                    ?: HashMap<Int, List<IntRange>>().also { highlightsByIndex = it }
                for (offset in 0 until paired) {
                    val deleteLine = lines[deleteStart + offset]
                    val addLine = lines[addStart + offset]
                    val result = computeWordDiff(deleteLine.text, addLine.text) ?: continue
                    if (result.oldRanges.isNotEmpty()) map[deleteStart + offset] = result.oldRanges
                    if (result.newRanges.isNotEmpty()) map[addStart + offset] = result.newRanges
                }
            }
        } else {
            index++
        }
    }

    lines.forEachIndexed { lineIndex, line ->
        into.add(
            ReviewRow.Line(
                id = "$fileId:h$hunkIndex:$lineIndex",
                fileId = fileId,
                kind = line.kind,
                oldLine = line.oldLine,
                newLine = line.newLine,
                text = line.text,
                highlights = highlightsByIndex?.get(lineIndex).orEmpty(),
            ),
        )
    }
}
