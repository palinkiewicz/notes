package pl.dakil.notes.editor

import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownActions
import pl.dakil.notes.editor.markdown.MarkdownRenderer
import pl.dakil.notes.editor.markdown.MdStyle
import kotlin.random.Random
import org.junit.Assert.assertEquals

/**
 * Random sequences of formatting presses, checked against what the presses were supposed to mean.
 *
 * This exists because the bar's behaviour could not be got right one bug at a time. Every fix to
 * the old marker-shuffling code uncovered the next case — a selection reaching from outside a tag
 * to inside it, an edge the field had widened over a hidden `**`, two marker runs that came to
 * touch — and the reports kept arriving because the *representation* was wrong, not the arithmetic.
 * Formatting now goes through [pl.dakil.notes.editor.markdown.InlineModel] instead, and this is how
 * that is known to hold: thousands of sequences of presses at random places, with three things
 * asserted after every single one.
 *
 * 1. **The words survive.** Whatever happens to the markup, the reader sees exactly the characters
 *    they saw before. Syntax appearing on screen is the failure people actually notice.
 * 2. **The style lands where it was asked for, and nowhere else.** Read off the render plan rather
 *    than off the model, so this is a check of the whole round trip and not of the model against
 *    itself.
 * 3. **The selection still holds the same words**, counted in characters the reader can see, which
 *    is the only coordinate the two versions of a line agree on. A wrong answer here is what made
 *    the *next* press destroy a document, so it matters as much as the edit does.
 *
 * When it fails it prints the whole sequence that got there, which is the seed's worth of history
 * needed to write a named test for the case. Several of the tests in [MarkdownAttributesTest] began
 * life as output from this one.
 */
class MarkdownStyleFuzzTest {

    private val markers = listOf("**" to MdStyle.BOLD, "*" to MdStyle.ITALIC, "~~" to MdStyle.STRIKE)

    /** Characters the renderer put there itself, which are not the user's prose. */
    private val BLOCK_GLYPHS = setOf(MdStyle.MARKER, MdStyle.INDENT, MdStyle.TASK_BOX)

    private val PREFIXES = listOf(
        Regex("^#{1,6}\\s+"),
        Regex("^>\\s?"),
        Regex("^[-*+]\\s+\\[[ xX]]\\s*"),
        Regex("^[-*+]\\s+"),
        Regex("^\\d+[.)]\\s+"),
    )

    private fun hidden(src: String, at: Int) = MarkdownRenderer.hidesAnythingIn(src, at, at + 1)

    /** Source offsets of the characters the reader can see, in order. */
    private fun visibleOffsets(src: String): List<Int> = src.indices.filter { !hidden(src, it) }

    /**
     * Whether [at] is inside a line's block markers rather than its prose.
     *
     * A bullet and a checkbox are drawn rather than typed — the renderer puts a glyph and a blank
     * where they stood — so they are not characters a style can be put on, and the oracle has no
     * business asking about them. The app decides this with `MarkdownActions.prefixOf`; this is the
     * same rule written out again on purpose, so a mistake in one does not excuse the other.
     */
    private fun inPrefix(src: String, at: Int): Boolean {
        val lineStart = src.lastIndexOf('\n', (at - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = src.indexOf('\n', lineStart).let { if (it < 0) src.length else it }
        var body = lineStart
        val line = src.substring(lineStart, lineEnd)
        var rest = line
        while (true) {
            val match = PREFIXES.firstNotNullOfOrNull { it.find(rest)?.value } ?: break
            body += match.length
            rest = rest.substring(match.length)
        }
        return at < body
    }

    private fun visibleText(src: String): String =
        visibleOffsets(src).map { src[it] }.joinToString("")

    /** Styles per visible character, read off the plan in the plan's own coordinates. */
    private fun map(src: String): List<Set<MdStyle>> {
        val plan = MarkdownRenderer.plan(src)
        return visibleOffsets(src).map { at ->
            val t = MarkdownRenderer.transformedOffset(plan.edits, at)
            plan.styles.filter { t >= it.start && t < it.end }.map { it.style }.toSet()
        }
    }

    private fun sizes(src: String): List<Int?> {
        val plan = MarkdownRenderer.plan(src)
        return visibleOffsets(src).map { at ->
            val t = MarkdownRenderer.transformedOffset(plan.edits, at)
            plan.styles.lastOrNull { it.style == MdStyle.SIZE && t >= it.start && t < it.end }?.arg
        }
    }

    @Test
    fun `no sequence of presses breaks the document or misplaces a style`() {
        // Documents with an atom in them — a code span, a link — are checked for the invariants
        // that matter everywhere (the words survive, the selection is where it was) but not for
        // per-character styles: an atom is one thing, and bolding half of one bolds all of it.
        val starts = listOf(
            "texttestofcolors" to true,
            "alpha beta gamma" to true,
            "one two" to true,
            "a `code` b" to false,
            "see [label](http://x) here" to false,
            "one two\nthree four" to true,
            "- alpha beta\n- gamma delta" to true,
            "# heading here\n\nbody text" to true,
            "x **bold** y *em* z" to true,
            "pre [sized]{size=24} post" to true,
            "**[bold sized]{size=24}** tail" to true,
            "a *b* c **d** e ~~f~~ g" to true,
            "> quoted words here" to true,
            "1. first item\n2. second item" to true,
            // Non-strict: a checkbox is a four-character `[ ]` replaced by a three-column blank,
            // so this oracle's source-to-rendered mapping is a character out along the line and it
            // reads the style of the wrong letter. The invariants that matter — the words survive
            // and the selection stays put — are still checked here.
            "- [ ] task one\n- [x] task two" to false,
            "\$math\$ and text" to false,
            "mixed [a]{color=#ff0000} and **b**" to true,
        )
        var failures = 0
        for (seed in 0 until 4000) {
            val rng = Random(seed)
            val (start, strict) = starts[seed % starts.size]
            var src = start
            val history = StringBuilder("start: ${start.replace("\n", "\\n")}\n")
            repeat(16) {
                val offsets = visibleOffsets(src)
                val text = visibleText(src)
                if (text.length < 2) return@repeat
                val a = rng.nextInt(text.length)
                val b = a + 1 + rng.nextInt(text.length - a)
                val from = offsets[a]
                val to = if (b < offsets.size) offsets[b] else src.length

                val before = map(src)
                val beforeSizes = sizes(src)

                val roll = rng.nextInt(6)
                val result: MarkdownActions.Result
                val label: String
                var style: MdStyle? = null
                when {
                    roll == 0 -> {
                        val sp = listOf(null, 12, 24)[rng.nextInt(3)]
                        result = MarkdownActions.setSize(src, from, to, sp)
                        label = "size=$sp"
                    }
                    roll == 1 -> {
                        val argb = listOf(null, 0xFF0000FF.toInt())[rng.nextInt(2)]
                        result = MarkdownActions.setColor(src, from, to, argb)
                        label = "color=$argb"
                    }
                    else -> {
                        val (marker, kind) = markers[rng.nextInt(markers.size)]
                        style = kind
                        result = MarkdownActions.toggleWrap(src, from, to, marker)
                        label = marker
                    }
                }
                history.append("  $label over '${text.substring(a, b)}'[$a,$b)  ->  ${result.text.replace("\n", "\\n")}\n")
                val beforeSrc = src
                src = result.text
                // Recomputed: the source offsets of the same visible characters have moved, and
                // asking the new document about the old ones is how an oracle invents failures.
                val afterOffsets = visibleOffsets(src)

                fun fail(why: String): Boolean {
                    println("SEED $seed $why\n$history")
                    failures++
                    return true
                }

                if (visibleText(src) != text) {
                    if (fail("WORDS CHANGED: '$text' -> '${visibleText(src)}'")) return@repeat
                }

                val selA = (0 until result.selectionStart.coerceIn(0, src.length)).count { !hidden(src, it) }
                val selB = (0 until result.selectionEnd.coerceIn(0, src.length)).count { !hidden(src, it) }
                if (selA != a || selB != b) {
                    if (fail("SELECTION want [$a,$b) got [$selA,$selB)")) return@repeat
                }

                if (!strict) return@repeat
                val after = map(src)
                val afterSizes = sizes(src)
                if (style != null) {
                    // Blanks have no say, matching the rule the bar follows: a marker may not
                    // touch whitespace, so the space between two bold words is not always bold.
                    val voters = (a until b).filterNot { text[it].isWhitespace() }
                        .filterNot { i -> BLOCK_GLYPHS.any { it in before[i] } }
                        .filterNot { i -> inPrefix(beforeSrc, offsets[i]) }
                    val wasOn = voters.isNotEmpty() && voters.all { style in before[it] }
                    val wrong = text.indices.filter { i ->
                        if (text[i].isWhitespace()) return@filter false
                        // A bullet is not prose: it is a substituted glyph, and no marker may be
                        // put round one.
                        if (BLOCK_GLYPHS.any { it in before[i] || it in after[i] }) return@filter false
                        if (i < afterOffsets.size && inPrefix(src, afterOffsets[i])) return@filter false
                        val want = when {
                            i !in a until b -> style in before[i]
                            else -> !wasOn
                        }
                        (style in after[i]) != want
                    }
                    if (wrong.isNotEmpty()) {
                        if (fail("$style WRONG at $wrong (wasOn=$wasOn)")) return@repeat
                    }
                } else if (roll == 0) {
                    val wrong = text.indices.filter { i ->
                        if (text[i].isWhitespace() || i in a until b) false
                        else if (BLOCK_GLYPHS.any { it in before[i] || it in after[i] }) false
                        else if (i < afterOffsets.size && inPrefix(src, afterOffsets[i])) false
                        else afterSizes[i] != beforeSizes[i]
                    }
                    if (wrong.isNotEmpty()) {
                        if (fail("SIZE LEAKED at $wrong")) return@repeat
                    }
                }
            }
            if (failures >= 8) break
        }
        assertEquals("sequences that broke the document or its styling", 0, failures)
    }
}
