package com.therapylocal.app.ui.logic

import com.therapylocal.app.memory.Cue
import java.text.Collator
import java.util.Locale

/**
 * The client reel's index: names in alphabetical order (locale-aware, accents ignored), the distinct first letters for the rail, and
 * where each letter starts. Pure, so the rail rules are unit tested.
 */
class ReelIndex(names: List<String>, locale: Locale = Locale.getDefault()) {
    private val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }

    /** Positions into the input list, in display order. */
    val order: List<Int> = names.indices.sortedWith { a, b -> collator.compare(names[a].trim(), names[b].trim()) }

    /** One letter per group, in order. Names that do not start with a letter share "#", which comes first. */
    val letters: List<String>

    /** Display position of the first name of each letter. */
    val firstOf: List<Int>

    /** The letter (index into [letters]) of each display position. */
    val letterOf: List<Int>

    init {
        val l = ArrayList<String>()
        val f = ArrayList<Int>()
        val of = ArrayList<Int>()
        order.forEachIndexed { pos, i ->
            val key = letterKey(names[i])
            if (l.lastOrNull() != key) {
                l += key
                f += pos
            }
            of += l.size - 1
        }
        letters = l
        firstOf = f
        letterOf = of
    }

    val size: Int get() = order.size

    companion object {
        /** "Émile" -> "E", "élan" -> "E", "3rd" -> "#". */
        fun letterKey(name: String): String {
            val c = java.text.Normalizer.normalize(name.trim(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").firstOrNull() ?: return "#"
            return if (c.isLetter()) c.uppercaseChar().toString() else "#"
        }
    }
}

/**
 * The letter rail is a pointer, not a second scroll: the letter under the finger is the letter you get. Down the rail is always later in
 * the alphabet, whatever the finger did before, so it can never feel like it runs the opposite way to the list.
 * [y] is the finger's position, [top] and [height] the rail's extent, [count] the number of letters. Positions past either end clamp.
 */
fun railLetterAt(y: Float, top: Float, height: Float, count: Int): Int {
    if (count <= 1 || height <= 0f) return 0
    val f = ((y - top) / height).coerceIn(0f, 0.99999f)
    return (f * count).toInt()
}

/** Centre of letter [i] on a rail of [count] letters (for drawing, the inverse of [railLetterAt]). */
fun railLetterY(i: Int, top: Float, height: Float, count: Int): Float =
    if (count <= 0) top else top + height * (i + 0.5f) / count

/**
 * What the recording screen shows, from the live ranked list. Cues are re-scored every couple of seconds; showing the raw top would make
 * the big statement change under the therapist's eyes. So the shown cue stays at least [dwellMs] and is only replaced by one that beats it
 * by [margin], unless it drops out of the list entirely. [also] are the next ones, smaller.
 */
data class CueView(val main: Cue?, val since: Long, val also: List<Cue>) {
    companion object {
        val EMPTY = CueView(null, 0, emptyList())

        fun next(prev: CueView, cues: List<Cue>, now: Long, dwellMs: Long = 8_000, margin: Float = 0.03f, alsoCount: Int = 2): CueView {
            val top = cues.firstOrNull() ?: return EMPTY
            val cur = prev.main?.let { m -> cues.firstOrNull { it.factId == m.factId } }
            val main = when {
                cur == null -> top
                top.factId == cur.factId -> cur
                now - prev.since >= dwellMs && top.score > cur.score + margin -> top
                else -> cur
            }
            val since = if (main.factId == prev.main?.factId) prev.since else now
            return CueView(main, since, cues.filter { it.factId != main.factId }.take(alsoCount))
        }
    }
}

/** "client_20261012-140301" -> "2026-10-12"; anything else -> null. Session ids carry their start (see Ids.sessionId). */
fun sessionDate(sessionId: String): String? {
    val stamp = sessionId.substringAfter('_', "").takeIf { it.length >= 8 } ?: return null
    val d = stamp.take(8)
    if (!d.all { it.isDigit() }) return null
    return "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)}"
}

/** "George M." -> ("George", "M."): the reel shows the first word large and the rest in italics after it, on the one line. */
fun splitName(name: String): Pair<String, String?> {
    val t = name.trim().replace(Regex("\\s+"), " ")
    val i = t.indexOf(' ')
    return if (i <= 0) t to null else t.substring(0, i) to t.substring(i + 1)
}

/** Characters of a selected name that fit its line at full size. */
const val HERO_FULL_CHARS = 10

/** The smallest a long selected name is set, as a share of full size; anything longer than fits at it ends in an ellipsis. */
const val HERO_MIN_SHRINK = 0.6f

/** The share of full size a selected name of [chars] characters is set at, so it stays on one line as long as it reasonably can. */
fun heroShrink(chars: Int): Float = if (chars <= HERO_FULL_CHARS) 1f else (HERO_FULL_CHARS.toFloat() / chars).coerceAtLeast(HERO_MIN_SHRINK)

/** A time-of-day greeting from the hour (0..23). */
fun greeting(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}
