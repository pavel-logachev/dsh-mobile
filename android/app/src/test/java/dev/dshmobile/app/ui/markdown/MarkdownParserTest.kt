package dev.dshmobile.app.ui.markdown

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class MarkdownParserTest {
    @Test(timeout = 2000) fun `maximum length heading with internal spaces parses within frame safety budget`() {
        val text = "# a" + " ".repeat(131_068) + "x"
        val started = System.nanoTime()
        val document = parseMarkdown(text)
        assertUnder200Millis(started)
        assertEquals(MarkdownBlock.Heading(1, "a" + " ".repeat(8191)), document.blocks.single())
        assertTrue(document.truncated)
    }

    @Test(timeout = 2000) fun `maximum length spaced rule is stack safe and linear`() {
        val text = "- ".repeat(65_536)
        val started = System.nanoTime()
        val document = parseMarkdown(text)
        assertUnder200Millis(started)
        assertEquals(listOf(MarkdownBlock.Rule), document.blocks)
        assertFalse(document.truncated)
    }

    @Test(timeout = 2000) fun `list marker whitespace before a Unicode line separator is linear`() {
        val text = "- " + "\t".repeat(131_068) + "\u2028x"
        val started = System.nanoTime()
        val document = parseMarkdown(text)
        assertUnder200Millis(started)
        assertTrue(document.blocks.all { it is MarkdownBlock.Paragraph })
    }

    @Test(timeout = 2000) fun `fence marker run before a Unicode line separator is linear`() {
        val text = "`".repeat(131_070) + "\u2028x"
        val started = System.nanoTime()
        val document = parseMarkdown(text)
        assertUnder200Millis(started)
        assertTrue(document.blocks.all { it is MarkdownBlock.Paragraph })
    }

    @Test(timeout = 2000) fun `maximum delimiter runs stay bounded in blocks and inline spans`() {
        for (marker in listOf('*', '_', '`')) {
            val text = marker.toString().repeat(131_072)
            val started = System.nanoTime()
            val document = parseMarkdown(text)
            document.blocks.filterIsInstance<MarkdownBlock.Paragraph>().forEach { parseInline(it.text) }
            assertEquals(text, parseInline(text).joinToString("") { it.text })
            parseInline(marker.toString().repeat(16_384)) // Also exercise the indexed path, not just its length guard.
            assertUnder200Millis(started)
        }
    }

    @Test(timeout = 2000) fun `deep list indentation and whitespace list markers remain linear`() {
        val text = " ".repeat(131_062) + "- nested!!"
        val started = System.nanoTime()
        val document = parseMarkdown(text)
        assertUnder200Millis(started)
        assertTrue(document.blocks.all { it is MarkdownBlock.Paragraph })
        assertEquals(text, document.blocks.joinToString("") { (it as MarkdownBlock.Paragraph).text })
    }

    @Test(timeout = 2000) fun `maximum table row and separator keep bounded cells`() {
        val text = "| a | b |\n| --- | :---: |\n|" + "x".repeat(131_040) + "| y |"
        val started = System.nanoTime()
        val document = parseMarkdown(text)
        assertUnder200Millis(started)
        val table = document.blocks.single() as MarkdownBlock.Table
        assertEquals(listOf("a", "b"), table.header)
        assertEquals("x".repeat(8192), table.rows.single().first())
        assertTrue(document.truncated)
        val separator = "a | b\n" + "-".repeat(131_060) + " | ---"
        val separatorStarted = System.nanoTime()
        assertTrue(parseMarkdown(separator).blocks.single() is MarkdownBlock.Table)
        assertUnder200Millis(separatorStarted)
    }

    @Test(timeout = 2000) fun `unclosed streaming fence followed by 100k characters preserves all copy text`() {
        val code = "x".repeat(100_000)
        val started = System.nanoTime()
        val document = parseMarkdown("```kotlin\n$code")
        assertUnder200Millis(started)
        assertEquals(code, document.blocks.joinToString("") { (it as MarkdownBlock.Code).text })
        assertTrue(document.blocks.all { it is MarkdownBlock.Code && !it.closed && it.copyText == code })
        assertFalse(document.truncated)
    }

    @Test fun `scanner edge cases preserve original whitespace and marker semantics`() {
        assertEquals(MarkdownBlock.Paragraph("# "), parseMarkdown("# ").blocks.single())
        assertEquals(MarkdownBlock.Heading(1, " "), parseMarkdown("#  ").blocks.single())
        assertEquals(MarkdownBlock.Heading(3, "Title"), parseMarkdown("   ###\tTitle ###\t").blocks.single())
        assertEquals(MarkdownBlock.ListEntry("•", "\t", 0), parseMarkdown("- \t").blocks.single())
        assertEquals(MarkdownBlock.ListEntry("123456789)", "item", 1), parseMarkdown("      123456789) item").blocks.single())
        assertTrue(parseMarkdown("       - item").blocks.single() is MarkdownBlock.Paragraph)
        assertTrue(parseMarkdown("1234567890. item").blocks.single() is MarkdownBlock.Paragraph)
        assertEquals(MarkdownBlock.Rule, parseMarkdown("   _ \t_\u000b_").blocks.single())
        assertEquals(MarkdownBlock.Code("lang`", "", false), parseMarkdown("```lang`").blocks.single())
        assertEquals(MarkdownBlock.Table(listOf("a", "b"), emptyList()), parseMarkdown("a | b\n: - - - : | ---").blocks.single())
    }

    private fun assertUnder200Millis(started: Long) {
        val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("Parsing took $millis ms; budget is 200 ms", millis < 200)
    }

    @Test fun `inline emphasis code and inert links render independent spans`() {
        val result = parseInline("**bold** *italic* `x < y` [label](javascript:alert) <script>")
        assertTrue(result.any { it.text == "bold" && it.bold })
        assertTrue(result.any { it.text == "italic" && it.italic })
        assertTrue(result.any { it.text == "x < y" && it.code })
        assertTrue(result.any { it.text == "label" && it.link == "javascript:alert" })
        assertTrue(result.last().text.contains("<script>"))
    }

    @Test fun `streaming partial fences and emphasis preserve readable content`() {
        assertEquals(MarkdownBlock.Code("python", "print(1)", false), parseMarkdown("```python\nprint(1)").blocks.single())
        assertEquals("**unfinished *text", parseInline("**unfinished *text").joinToString("") { it.text })
        assertEquals("*escaped*", parseInline("\\*escaped\\*").joinToString("") { it.text })
    }

    @Test(timeout = 2000) fun `huge malformed input is bounded without backtracking or recursion overflow`() {
        val doc = parseMarkdown("<script>alert(1)</script>\n".repeat(50_000))
        assertTrue(doc.truncated)
        assertTrue(doc.blocks.size <= 512)
        assertTrue(doc.blocks.all { it is MarkdownBlock.Paragraph })
        val hostile = "[x(".repeat(2000)
        assertEquals(hostile, parseInline(hostile).joinToString("") { it.text })
    }

    @Test fun `tilde fences longer closing and one nested list level are supported`() {
        val doc = parseMarkdown("~~~~sh\necho hi\n~~~~~\n\n    - nested\n      - also nested")
        assertEquals(MarkdownBlock.Code("sh", "echo hi", true), doc.blocks.first())
        assertEquals(1, (doc.blocks[1] as MarkdownBlock.ListEntry).depth)
        assertEquals(1, (doc.blocks[2] as MarkdownBlock.ListEntry).depth)
    }

    @Test fun `empty and CRLF inputs are safe and malformed tables stay text`() {
        assertTrue(parseMarkdown("").blocks.isEmpty())
        assertEquals(MarkdownBlock.Heading(2, "Title"), parseMarkdown("## Title\r\n\r\ntext").blocks.first())
        assertTrue(parseMarkdown("a | b\n-- | --").blocks.all { it is MarkdownBlock.Paragraph })
    }

    @Test fun `lists quotes tables and rules are native structured blocks`() {
        val doc = parseMarkdown("- first\n  - nested\n1. ordered\n\n> quoted\n\n---\n\n| Key | Value |\n| --- | :---: |\n| a | b |")
        assertEquals(MarkdownBlock.ListEntry("•", "first", 0), doc.blocks[0])
        assertEquals(MarkdownBlock.ListEntry("•", "nested", 1), doc.blocks[1])
        assertEquals(MarkdownBlock.ListEntry("1.", "ordered", 0), doc.blocks[2])
        assertEquals(MarkdownBlock.Quote("quoted"), doc.blocks[3])
        assertEquals(MarkdownBlock.Rule, doc.blocks[4])
        assertEquals(MarkdownBlock.Table(listOf("Key", "Value"), listOf(listOf("a", "b"))), doc.blocks[5])
    }

    @Test fun `split fenced code retains whole-fence copy text and leading blank line`() {
        val text = "\n" + "x".repeat(9000)
        val code = parseMarkdown("```kotlin\n$text\n```").blocks.map { it as MarkdownBlock.Code }
        assertEquals(2, code.size)
        assertEquals(text, code.joinToString("") { it.text })
        assertTrue(code.all { it.copyText == text && it.closed })
    }

    @Test fun `native prose preserves Windows paths and literal backslashes`() {
        val path = "C:\\Users\\example\\project\\script.kt"
        assertEquals(path, parseInline(path).joinToString("") { it.text })
        assertEquals("*literal* and \\server", parseInline("\\*literal\\* and \\server").joinToString("") { it.text })
    }

    @Test fun `bounded headers and tables report omitted cells instead of silently dropping text`() {
        assertTrue(parseMarkdown("# " + "x".repeat(8193)).truncated)
        val header = (1..13).joinToString(" | ") { "h$it" }
        val separator = (1..13).joinToString(" | ") { "---" }
        assertTrue(parseMarkdown("$header\n$separator").truncated)
        assertEquals("Heading#", (parseMarkdown("# Heading#").blocks.single() as MarkdownBlock.Heading).text)
        assertEquals("Heading", (parseMarkdown("# Heading ###").blocks.single() as MarkdownBlock.Heading).text)
    }

    @Test fun `headings paragraphs and native code fences retain text`() {
        val doc = parseMarkdown("# Result\n\nDone\n\n```kotlin\nval x = 1\n```")
        assertEquals(3, doc.blocks.size)
        assertEquals(MarkdownBlock.Heading(1, "Result"), doc.blocks[0])
        assertEquals(MarkdownBlock.Paragraph("Done"), doc.blocks[1])
        assertEquals(MarkdownBlock.Code("kotlin", "val x = 1", true), doc.blocks[2])
        assertFalse(doc.truncated)
    }
}
