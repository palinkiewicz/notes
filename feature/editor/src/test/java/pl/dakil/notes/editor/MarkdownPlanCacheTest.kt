package pl.dakil.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.dakil.notes.editor.markdown.MarkdownRenderer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The plan cache, and the cost of asking the plan questions on a long note.
 *
 * Export and its page survey plan a note on a background dispatcher while the editor plans the same
 * note on the main thread. A cache that can hand one thread the plan of another thread's string
 * applies the wrong offsets to a text field, which throws or garbles what the user is looking at.
 */
class MarkdownPlanCacheTest {

    @Test
    fun `plans asked for on several threads at once are each the plan of their own text`() {
        val documents = List(8) { i -> "# Heading $i\n\n**bold $i** and `code`\n\n- item ${"x".repeat(i)}\n" }
        val expected = documents.map { renderedRaw(it) }
        val failure = AtomicReference<Throwable?>(null)
        val pool = Executors.newFixedThreadPool(documents.size)
        val start = CountDownLatch(1)
        for ((index, document) in documents.withIndex()) {
            pool.execute {
                start.await()
                try {
                    repeat(20_000) {
                        val actual = renderedRaw(document)
                        if (actual != expected[index]) error("plan of another document for #$index: $actual")
                    }
                } catch (cause: Throwable) {
                    failure.compareAndSet(null, cause)
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(2, TimeUnit.MINUTES))
        failure.get()?.let { throw AssertionError(it) }
    }

    @Test
    fun `a long note plans and answers visibility questions in a bounded time`() {
        // Every line carries an emphasis opener with no closer: the shape that sent the old scan
        // looking for one all the way to the end of the document, once per line.
        val line = "Some *unclosed text with **bold** and `code`, plus a [link](u) and _more\n"
        val document = line.repeat(200_000 / line.length)
        val started = System.nanoTime()
        MarkdownRenderer.plan(document)
        MarkdownRenderer.visibleSpan(document, 0, document.length)
        MarkdownRenderer.hidesAnythingIn(document, document.length / 2, document.length)
        MarkdownRenderer.coversVisibleText(document, 0, document.length)
        val millis = (System.nanoTime() - started) / 1_000_000
        // Generous on purpose: this is here to catch a quadratic walk, which takes minutes, not to
        // time anything.
        assertTrue("took $millis ms", millis < 3_000)
    }

    @Test
    fun `the answers the editor reads off a plan do not change with caching`() {
        val document = "# a\n**b** `c`\n"
        val first = MarkdownRenderer.plan(document)
        MarkdownRenderer.plan("something else entirely")
        assertEquals(first, MarkdownRenderer.plan(document))
    }
}
