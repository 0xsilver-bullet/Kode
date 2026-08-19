package com.silverbullet.kode.feature.threads.domain.review

import io.github.petertrr.diffutils.diff
import io.github.petertrr.diffutils.patch.DeltaType

/**
 * Word-level intra-line highlights for a paired delete/add row, computed with
 * `kotlin-multiplatform-diff` (Myers over word tokens) and gated by the same
 * caps as t3code's native adapter — highlights that cover most of the line are
 * noise, so they are dropped rather than rendered:
 *
 *  - lines longer than 1 000 chars are skipped (`REVIEW_MAX_WORD_ALT_LINE_LENGTH`)
 *  - a side keeps at most 4 ranges (`NATIVE_REVIEW_MAX_WORD_DIFF_RANGE_COUNT`)
 *  - highlighted non-whitespace must stay ≤ 45 % of the side's non-whitespace
 *    (`NATIVE_REVIEW_MAX_WORD_DIFF_COVERAGE`)
 *  - whitespace is trimmed off range edges
 */

private const val MAX_LINE_LENGTH = 1_000
private const val MAX_RANGE_COUNT = 4
private const val MAX_COVERAGE = 0.45

class WordDiffResult(
    /** Half-open char ranges to highlight in the delete row's text. */
    val oldRanges: List<IntRange>,
    /** Half-open char ranges to highlight in the add row's text. */
    val newRanges: List<IntRange>,
)

fun computeWordDiff(oldText: String, newText: String): WordDiffResult? {
    if (oldText.length > MAX_LINE_LENGTH || newText.length > MAX_LINE_LENGTH) return null
    if (oldText.isEmpty() || newText.isEmpty()) return null

    val oldTokens = tokenize(oldText)
    val newTokens = tokenize(newText)
    val patch = diff(oldTokens, newTokens)
    if (patch.deltas.isEmpty()) return null

    val oldOffsets = tokenOffsets(oldTokens)
    val newOffsets = tokenOffsets(newTokens)

    val oldRanges = ArrayList<IntRange>()
    val newRanges = ArrayList<IntRange>()
    for (delta in patch.deltas) {
        if (delta.type == DeltaType.DELETE || delta.type == DeltaType.CHANGE) {
            appendTokenRange(oldRanges, oldOffsets, delta.source.position, delta.source.lines.size)
        }
        if (delta.type == DeltaType.INSERT || delta.type == DeltaType.CHANGE) {
            appendTokenRange(newRanges, newOffsets, delta.target.position, delta.target.lines.size)
        }
    }

    val trimmedOld = trimAndCap(oldRanges, oldText) ?: return null
    val trimmedNew = trimAndCap(newRanges, newText) ?: return null
    if (trimmedOld.isEmpty() && trimmedNew.isEmpty()) return null
    return WordDiffResult(oldRanges = trimmedOld, newRanges = trimmedNew)
}

/**
 * Splits into word runs, whitespace runs, and single symbol chars — the
 * granularity `diffWordsWithSpace` uses, which keeps identifiers atomic.
 */
private fun tokenize(text: String): List<String> {
    val tokens = ArrayList<String>(text.length / 4 + 1)
    var index = 0
    while (index < text.length) {
        val char = text[index]
        val begin = index
        when {
            char.isLetterOrDigit() || char == '_' -> {
                while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) {
                    index++
                }
            }

            char == ' ' || char == '\t' -> {
                while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index++
            }

            else -> index++
        }
        tokens.add(text.substring(begin, index))
    }
    return tokens
}

/** Prefix sums: `offsets[i]` is the char position where token `i` starts. */
private fun tokenOffsets(tokens: List<String>): IntArray {
    val offsets = IntArray(tokens.size + 1)
    for (index in tokens.indices) {
        offsets[index + 1] = offsets[index] + tokens[index].length
    }
    return offsets
}

private fun appendTokenRange(
    into: ArrayList<IntRange>,
    offsets: IntArray,
    position: Int,
    count: Int,
) {
    if (count <= 0) return
    val start = offsets[position]
    val end = offsets[(position + count).coerceAtMost(offsets.size - 1)]
    if (end <= start) return
    // Merge with the previous range when adjacent, so "a b" changing wholly
    // counts as one range against the cap instead of three.
    val last = into.lastOrNull()
    if (last != null && last.last + 1 >= start) {
        into[into.size - 1] = last.first..(end - 1)
    } else {
        into.add(start..(end - 1))
    }
}

/** Trims whitespace off range edges, then applies the count and coverage caps. */
private fun trimAndCap(ranges: List<IntRange>, text: String): List<IntRange>? {
    val trimmed = ArrayList<IntRange>(ranges.size)
    for (range in ranges) {
        var start = range.first
        var end = range.last
        while (start <= end && text[start].isWhitespace()) start++
        while (end >= start && text[end].isWhitespace()) end--
        if (start <= end) trimmed.add(start..end)
    }
    if (trimmed.isEmpty()) return emptyList()
    if (trimmed.size > MAX_RANGE_COUNT) return null

    var totalNonWhitespace = 0
    for (char in text) {
        if (!char.isWhitespace()) totalNonWhitespace++
    }
    if (totalNonWhitespace == 0) return emptyList()

    var highlightedNonWhitespace = 0
    for (range in trimmed) {
        for (index in range) {
            if (!text[index].isWhitespace()) highlightedNonWhitespace++
        }
    }
    if (highlightedNonWhitespace.toDouble() / totalNonWhitespace > MAX_COVERAGE) return null
    return trimmed
}
