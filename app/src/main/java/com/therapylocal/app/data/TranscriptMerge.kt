package com.therapylocal.app.data

/**
 * Joins the transcripts of overlapping chunks without repeating the words that fall in the overlap.
 *
 * Consecutive chunks share ChunkConfig.OVERLAP_SECONDS of audio, so the end of chunk i and the start of chunk i+1 say roughly the
 * same words, but never identically: the speech recogniser hears the cut differently ("deserted bosom" against "dishonored bosom",
 * a word chopped at the boundary). So an exact match is not enough.
 *
 * For each pair this aligns the tail of the text so far against the head of the next chunk with a suffix-prefix (overlap)
 * alignment, which tolerates substitutions and dropped or extra words. The two chunks are then stitched in the MIDDLE of the
 * aligned region: the text so far is kept up to there and the next chunk is kept from there. The ragged edges, the end of one
 * chunk and the start of the next, are both the least reliable words, so neither side's edge survives. If the alignment is too
 * weak, the texts are simply joined.
 *
 * Alignment is the overlap form of Needleman-Wunsch: https://en.wikipedia.org/wiki/Needleman%E2%80%93Wunsch_algorithm
 */
object TranscriptMerge {
    private const val MAX_WORDS = 45
    private const val MATCH = 2
    private const val MISMATCH = -1
    private const val GAP = -1

    /** An alignment must reach this score and contain this many equal words, or it is treated as no overlap. */
    private const val MIN_SCORE = 6
    private const val MIN_MATCHES = 3

    private const val DIAG = 0
    private const val UP = 1 // consumes a word of the text so far only
    private const val LEFT = 2 // consumes a word of the next chunk only

    /** [removedWords] per chunk: words dropped from the previous text plus words dropped from this chunk, 0 if no overlap was found. */
    data class Result(val text: String, val removedWords: List<Int>)

    fun merge(texts: List<String>): Result {
        val words = ArrayList<String>()
        val removed = ArrayList<Int>()
        for ((i, t) in texts.withIndex()) {
            val next = t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (i == 0 || words.isEmpty()) {
                words += next
                removed += 0
                continue
            }
            val stitch = stitch(words, next)
            if (stitch == null) {
                words += next
                removed += 0
            } else {
                repeat(stitch.dropFromEnd) { words.removeAt(words.size - 1) }
                words += next.drop(stitch.dropFromStart)
                removed += stitch.dropFromEnd + stitch.dropFromStart
            }
        }
        return Result(words.joinToString(" "), removed)
    }

    /** Drop [dropFromEnd] words from the end of the text so far and [dropFromStart] words from the start of the next chunk. */
    private class Stitch(val dropFromEnd: Int, val dropFromStart: Int)

    private fun stitch(acc: List<String>, next: List<String>): Stitch? {
        val a = acc.takeLast(MAX_WORDS).map(::norm)
        val b = next.take(MAX_WORDS).map(::norm)
        if (a.isEmpty() || b.isEmpty()) return null
        val n = a.size
        val m = b.size
        val h = Array(n + 1) { IntArray(m + 1) }
        val dir = Array(n + 1) { IntArray(m + 1) }
        val matches = Array(n + 1) { IntArray(m + 1) }
        for (j in 1..m) {
            h[0][j] = j * GAP
            dir[0][j] = LEFT
        }
        for (i in 1..n) {
            for (j in 1..m) {
                val same = similar(a[i - 1], b[j - 1])
                val diag = h[i - 1][j - 1] + if (same) MATCH else MISMATCH
                val up = h[i - 1][j] + GAP
                val left = h[i][j - 1] + GAP
                val best = maxOf(diag, up, left)
                h[i][j] = best
                when (best) {
                    diag -> {
                        dir[i][j] = DIAG
                        matches[i][j] = matches[i - 1][j - 1] + if (same) 1 else 0
                    }
                    up -> {
                        dir[i][j] = UP
                        matches[i][j] = matches[i - 1][j]
                    }
                    else -> {
                        dir[i][j] = LEFT
                        matches[i][j] = matches[i][j - 1]
                    }
                }
            }
        }
        // The alignment must end at the end of the text so far; pick the prefix length of the next chunk that scores best.
        var bestJ = 0
        var bestScore = 0
        for (j in 1..m) {
            if (h[n][j] >= bestScore && h[n][j] > 0 && matches[n][j] >= MIN_MATCHES) {
                bestScore = h[n][j]
                bestJ = j
            }
        }
        if (bestScore < MIN_SCORE) return null

        // Trace back the aligned region: diagonal steps are (index in a, index in b) pairs.
        val pairs = ArrayList<Pair<Int, Int>>()
        var i = n
        var j = bestJ
        while (j > 0 && i > 0) {
            when (dir[i][j]) {
                DIAG -> {
                    pairs += (i - 1) to (j - 1)
                    i--
                    j--
                }
                UP -> i--
                else -> j--
            }
        }
        if (pairs.isEmpty()) return Stitch(0, bestJ)
        pairs.reverse()
        // Stitch at the middle diagonal pair: keep the text so far before it, and the next chunk from it.
        val (aMid, bMid) = pairs[pairs.size / 2]
        return Stitch(dropFromEnd = n - aMid, dropFromStart = bMid)
    }

    private fun norm(w: String) = w.lowercase().filter { it.isLetterOrDigit() }

    /** Equal after normalising, or one edit apart for longer words ("broffles" and "brofles"). */
    private fun similar(x: String, y: String): Boolean {
        if (x == y) return x.isNotEmpty()
        if (x.length < 5 || y.length < 5 || kotlin.math.abs(x.length - y.length) > 1) return false
        return editDistanceAtMostOne(x, y)
    }

    private fun editDistanceAtMostOne(x: String, y: String): Boolean {
        var i = 0
        var j = 0
        var edits = 0
        while (i < x.length && j < y.length) {
            if (x[i] == y[j]) {
                i++
                j++
            } else {
                if (++edits > 1) return false
                when {
                    x.length > y.length -> i++
                    x.length < y.length -> j++
                    else -> {
                        i++
                        j++
                    }
                }
            }
        }
        return edits + (x.length - i) + (y.length - j) <= 1
    }
}
