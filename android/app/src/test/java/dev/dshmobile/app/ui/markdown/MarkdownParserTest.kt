package dev.dshmobile.app.ui.markdown

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class MarkdownParserTest {
    @Test fun `block chunks keep emoji before across and after the UTF16 boundary intact`() {
        for (offset in listOf(8190, 8191, 8192)) {
            val value = "x".repeat(offset) + "\uD83D\uDE00tail"
            for (source in listOf(value, "```kotlin\n$value\n```", "- $value", "> $value")) {
                val document = parseMarkdown(source)
                val texts = document.blocks.flatMap(::blockTexts)
                assertEquals(value, texts.joinToString(""))
                assertEquals(if (offset == 8190) 8192 else offset, texts.first().length)
                assertTrue(texts.all { it.length <= 8192 })
                assertFalse(document.truncated)
                assertWellFormedSurrogates(document)
                document.blocks.filterIsInstance<MarkdownBlock.Code>().forEach {
                    assertEquals(value, it.copyText)
                    assertTrue(it.closed)
                }
            }
        }
    }

    @Test fun `overall text cap keeps emoji before across and after its boundary intact`() {
        for ((value, expected) in emojiCutCases(131_072)) {
            val document = parseMarkdown(value)
            assertEquals(expected, document.blocks.flatMap(::blockTexts).joinToString(""))
            assertTrue(document.truncated)
            assertWellFormedSurrogates(document)
        }
        val exact = "x".repeat(131_070) + "\uD83D\uDE00"
        val document = parseMarkdown(exact)
        assertEquals(exact, document.blocks.flatMap(::blockTexts).joinToString(""))
        assertFalse(document.truncated)
        assertWellFormedSurrogates(document)
    }

    @Test fun `heading cap keeps emoji before across and after its boundary intact`() {
        for ((value, expected) in emojiCutCases(8192)) {
            val document = parseMarkdown("# $value")
            assertEquals(MarkdownBlock.Heading(1, expected), document.blocks.single())
            assertTrue(document.truncated)
            assertWellFormedSurrogates(document)
        }
    }

    @Test fun `table header and row cell caps keep emoji before across and after their boundary intact`() {
        for ((value, expected) in emojiCutCases(8192)) {
            val document = parseMarkdown("$value | header\n--- | ---\n$value | row")
            assertEquals(
                MarkdownBlock.Table(listOf(expected, "header"), listOf(listOf(expected, "row"))),
                document.blocks.single(),
            )
            assertTrue(document.truncated)
            assertWellFormedSurrogates(document)
        }
    }

    @Test fun `fence language cap keeps emoji before across and after its boundary intact`() {
        for ((value, expected) in emojiCutCases(40)) {
            val document = parseMarkdown("```$value\ncode\n```")
            assertEquals(MarkdownBlock.Code(expected, "code", true), document.blocks.single())
            assertFalse(document.truncated)
            assertWellFormedSurrogates(document)
        }
    }

    private fun emojiCutCases(limit: Int): List<Pair<String, String>> = listOf(
        ("x".repeat(limit - 2) + "\uD83D\uDE00tail") to ("x".repeat(limit - 2) + "\uD83D\uDE00"),
        ("x".repeat(limit - 1) + "\uD83D\uDE00tail") to "x".repeat(limit - 1),
        ("x".repeat(limit) + "\uD83D\uDE00tail") to "x".repeat(limit),
    )

    private fun blockTexts(block: MarkdownBlock): List<String> = when (block) {
        is MarkdownBlock.Heading -> listOf(block.text)
        is MarkdownBlock.Paragraph -> listOf(block.text)
        is MarkdownBlock.Code -> listOf(block.text)
        is MarkdownBlock.ListEntry -> listOf(block.text)
        is MarkdownBlock.Quote -> listOf(block.text)
        is MarkdownBlock.Table -> block.header + block.rows.flatten()
        MarkdownBlock.Rule -> emptyList()
    }

    private fun assertWellFormedSurrogates(document: MarkdownDocument) {
        val texts = document.blocks.flatMap { block ->
            blockTexts(block) + if (block is MarkdownBlock.Code) listOf(block.language, block.copyText) else emptyList()
        }
        texts.forEach { text ->
            text.forEachIndexed { index, char ->
                if (char.isHighSurrogate()) {
                    assertTrue("Unpaired high surrogate at $index", text.getOrNull(index + 1)?.isLowSurrogate() == true)
                }
                if (char.isLowSurrogate()) {
                    assertTrue("Unpaired low surrogate at $index", text.getOrNull(index - 1)?.isHighSurrogate() == true)
                }
            }
        }
    }

    @Test fun `table pipes inside escapes or matching code spans stay in their cell`() {
        val table = parseMarkdown("| Name | Value |\n| --- | --- |\n| a\\|b | `c|d` |\n| ``a`|b`` | end\\| |\n| a\\\\| b |") .blocks.single() as MarkdownBlock.Table
        assertEquals(listOf("a\\|b", "`c|d`"), table.rows[0])
        assertEquals(listOf("``a`|b``", "end\\|"), table.rows[1])
        assertEquals(listOf("a\\\\", "b"), table.rows[2])
        assertEquals("a|b", parseInline(table.rows[0][0]).joinToString("") { it.text })
    }

    @Test fun `unmatched backtick does not swallow the rest of a table row`() {
        val table = parseMarkdown("a | b\n--- | ---\n`unclosed | value").blocks.single() as MarkdownBlock.Table
        assertEquals(listOf("`unclosed", "value"), table.rows.single())
    }

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
