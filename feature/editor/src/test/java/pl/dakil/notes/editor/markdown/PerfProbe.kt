package pl.dakil.notes.editor.markdown

import org.junit.Test

/** Scratch probe, deleted after measurements. */
class PerfProbe {

    private fun doc(b64Kb: Int, leadLines: Int = 150): String {
        val sb = StringBuilder()
        repeat(leadLines) { i ->
            when (i % 6) {
                0 -> sb.append("# Heading $i\n\n")
                1 -> sb.append("Some **bold** and *italic* and `code` text here.\n\n")
                2 -> sb.append("- item one\n- item two\n  - nested item\n\n")
                3 -> sb.append("> quoted line with **style**\n\n")
                4 -> sb.append("```kotlin\nfun x() = 1\n```\n\n")
                5 -> sb.append("| a | b |\n|---|---|\n| 1 | 2 |\n\n")
            }
        }
        sb.append("Here is the image:\n\n![Image 1][img-1]\n\n![Image 2][img-1]\n\n")
        sb.append("[img-1]: data:image/png;base64,")
        val payload = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        repeat(b64Kb * 1024 / payload.length + 1) { sb.append(payload) }
        sb.append("\n\nMore text after the image.\n")
        for (i in 0 until 20) sb.append("Trailing paragraph line $i with **bold** bits.\n\n")
        return sb.toString()
    }

    @Test
    fun probe() {
        val small = doc(b64Kb = 0)
        val big = doc(b64Kb = 4 * 1024) // ~4 MiB base64 line
        println("doc sizes: small=${small.length / 1024}K big=${big.length / 1024 / 1024}M")
        MarkdownRenderer.plan(small) // warm up JIT
        MarkdownRenderer.plan(big)

        fun timed(name: String, block: () -> Unit) {
            val best = (1..3).map {
                val t0 = System.nanoTime(); block(); System.nanoTime() - t0
            }.min()
            println("%-28s %8.1f ms".format(name, best / 1e6))
        }

        timed("small: plan", { MarkdownRenderer.plan(small) })
        timed("big: plan", { MarkdownRenderer.plan(big) })
        timed("big: plan (fresh str)", { MarkdownRenderer.plan(String(big.toCharArray())) })
        timed("big: render()", { MarkdownRenderer.render(big) })
        timed("big: toString 4MB", { big.toString() })
        timed("big: substring url", { big.substringAfter("[img-1]: data:image/png;base64,").trim() })
    }
}
