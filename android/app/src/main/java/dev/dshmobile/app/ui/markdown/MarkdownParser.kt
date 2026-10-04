package dev.dshmobile.app.ui.markdown

internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Code(val language: String, val text: String, val closed: Boolean, val copyText: String = text) : MarkdownBlock
    data class ListEntry(val marker: String, val text: String, val depth: Int) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object Rule : MarkdownBlock
}
internal data class MarkdownDocument(val blocks: List<MarkdownBlock>, val truncated: Boolean)
internal data class InlineSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val link: String? = null)

private const val MAX_TEXT = 131_072
private const val MAX_BLOCKS = 512
private const val BLOCK_TEXT = 8192
private data class HeadingMatch(val level: Int, val text: String)
private fun regexSpace(char: Char): Boolean = char in " \t\u000b\u000c\r\n"
private fun horizontalSpace(char: Char): Boolean = char == ' ' || char == '\t'
private fun heading(line: String): HeadingMatch? {
    var index = 0
    while (index < line.length && index < 3 && line[index] == ' ') index++
    val markerStart = index
    while (index < line.length && line[index] == '#') index++
    val level = index - markerStart
    if (level !in 1..3 || index >= line.length || !horizontalSpace(line[index])) return null
    val spaceStart = index
    while (index < line.length && horizontalSpace(line[index])) index++
    // The old nonempty capture retained the final separator character in an otherwise empty heading.
    if (index == line.length && index - spaceStart < 2) return null
    val start = if (index == line.length) index - 1 else index
    var end = line.length
    while (end > start + 1 && regexSpace(line[end - 1])) end--
    if ((start until end).any { dotLineSeparator(line[it]) }) return null
    var suffix = end
    while (suffix > start && line[suffix - 1] == '#') suffix--
    if (suffix < end && suffix > start && horizontalSpace(line[suffix - 1])) {
        while (suffix > start && horizontalSpace(line[suffix - 1])) suffix--
        end = suffix
    }
    return HeadingMatch(level, line.substring(start, end))
}
private fun dotLineSeparator(char: Char): Boolean = char in "\u0085\u2028\u2029"
private data class ListMatch(val marker: String, val text: String, val depth: Int)
private fun listEntry(line: String): ListMatch? {
    var index = 0
    while (index < line.length && index < 6 && line[index] == ' ') index++
    val indent = index
    val markerStart = index
    val marker = line.getOrNull(index) ?: return null
    if (marker in "-+*") index++
    else {
        while (index < line.length && line[index] in '0'..'9' && index - markerStart < 9) index++
        if (index == markerStart || line.getOrNull(index) !in listOf('.', ')')) return null
        index++
    }
    val markerEnd = index
    if (index >= line.length || !regexSpace(line[index])) return null
    val spaceStart = index
    while (index < line.length && regexSpace(line[index])) index++
    if (index == line.length) {
        if (index - spaceStart < 2) return null
        index-- // Preserve the old nonempty capture after greedy whitespace.
    }
    if ((index until line.length).any { dotLineSeparator(line[it]) }) return null
    return ListMatch(if (marker in "-+*") "•" else line.substring(markerStart, markerEnd), line.substring(index), if (indent >= 2) 1 else 0)
}
private data class FenceMatch(val marker: Char, val length: Int, val language: String)
private fun fence(line: String): FenceMatch? {
    var index = 0
    while (index < line.length && index < 3 && line[index] == ' ') index++
    val marker = line.getOrNull(index) ?: return null
    if (marker != '`' && marker != '~') return null
    val start = index
    while (index < line.length && line[index] == marker) index++
    if (index - start < 3 || (index until line.length).any { dotLineSeparator(line[it]) }) return null
    return FenceMatch(marker, index - start, line.substring(index).trim().take(40))
}
private fun rule(line: String): Boolean {
    var index = 0
    while (index < line.length && index < 3 && line[index] == ' ') index++
    val marker = line.getOrNull(index) ?: return false
    if (marker !in "*-_") return false
    var count = 0
    while (index < line.length) {
        if (line[index] != marker) return false
        count++
        index++
        while (index < line.length && regexSpace(line[index])) index++
    }
    return count >= 3
}
/** Every block recognizer makes a fixed number of monotonic scans; no regex engine or recursion. */
private fun tableSeparator(cell: String): Boolean {
    var index = 0
    fun skipSpaces() { while (index < cell.length && cell[index] == ' ') index++ }
    skipSpaces()
    if (cell.getOrNull(index) == ':') { index++; skipSpaces() }
    var dashes = 0
    while (cell.getOrNull(index) == '-') { dashes++; index++; skipSpaces() }
    if (cell.getOrNull(index) == ':') { index++; skipSpaces() }
    return dashes >= 3 && index == cell.length
}
private fun closesFence(line: String, opening: FenceMatch): Boolean {
    val trimmed = line.trim()
    return trimmed.length >= opening.length && trimmed.all { it == opening.marker }
}
private fun tableCells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
private const val ESCAPABLE = "\\`*_{}[]()#+-.!|>~"
private fun markdownEscape(value: String, index: Int): Boolean = value[index] == '\\' && index + 1 < value.length && value[index + 1] in ESCAPABLE
private fun tableStart(lines: List<String>, index: Int): Boolean = index + 1 < lines.size && lines[index].contains('|') &&
    tableCells(lines[index + 1]).let { it.size >= 2 && it.all(::tableSeparator) }

/** Bounded native presentation. HTML is literal, URLs are inert, parsing never performs I/O. */
internal fun parseMarkdown(text: String): MarkdownDocument {
    val bounded = text.take(MAX_TEXT).replace("\r\n", "\n").replace('\r', '\n')
    val lines = bounded.split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    var truncated = text.length > MAX_TEXT
    fun addChunks(value: String, make: (String) -> MarkdownBlock) {
        value.chunked(BLOCK_TEXT).ifEmpty { listOf("") }.forEach { chunk ->
            if (blocks.size < MAX_BLOCKS) blocks += make(chunk) else truncated = true
        }
    }
    fun startsBlock(at: Int): Boolean = fence(lines[at]) != null || heading(lines[at]) != null ||
        rule(lines[at]) || listEntry(lines[at]) != null || lines[at].trimStart().startsWith(">") || tableStart(lines, at)
    while (index < lines.size && blocks.size < MAX_BLOCKS) {
        val line = lines[index]
        if (line.isBlank()) { index++; continue }
        val opening = fence(line)
        val heading = heading(line)
        val list = listEntry(line)
        when {
            opening != null -> {
                val language = opening.language
                index++
                val code = StringBuilder()
                var codeLineCount = 0
                while (index < lines.size && !closesFence(lines[index], opening)) {
                    if (codeLineCount++ > 0) code.append('\n')
                    code.append(lines[index++])
                }
                val closed = index < lines.size
                if (closed) index++
                val fullCode = code.toString()
                addChunks(fullCode) { MarkdownBlock.Code(language, it, closed, copyText = fullCode) }
            }
            heading != null -> {
                if (heading.text.length > BLOCK_TEXT) truncated = true
                blocks += MarkdownBlock.Heading(heading.level, heading.text.take(BLOCK_TEXT)); index++
            }
            rule(line) -> { blocks += MarkdownBlock.Rule; index++ }
            list != null -> {
                addChunks(list.text) { MarkdownBlock.ListEntry(list.marker, it, list.depth) }
                index++
            }
            line.trimStart().startsWith(">") -> {
                val quote = StringBuilder()
                while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(lines[index++].trimStart().removePrefix(">").removePrefix(" "))
                }
                addChunks(quote.toString()) { MarkdownBlock.Quote(it) }
            }
            tableStart(lines, index) -> {
                val headerCells = tableCells(line)
                if (headerCells.size > 12) truncated = true
                val header = headerCells.take(12).map { if (it.length > BLOCK_TEXT) truncated = true; it.take(BLOCK_TEXT) }
                val rows = mutableListOf<List<String>>()
                index += 2
                while (index < lines.size && lines[index].isNotBlank() && lines[index].contains('|') && rows.size < 64) {
                    rows += tableCells(lines[index++]).let { cells ->
                        if (cells.size > header.size) truncated = true
                        List(header.size) {
                            val cell = cells.getOrNull(it).orEmpty()
                            if (cell.length > BLOCK_TEXT) truncated = true
                            cell.take(BLOCK_TEXT)
                        }
                    }
                }
                blocks += MarkdownBlock.Table(header, rows)
                if (rows.size == 64 && index < lines.size && lines[index].contains('|')) truncated = true
            }
            else -> {
                val paragraph = StringBuilder(line); index++
                while (index < lines.size && lines[index].isNotBlank() && !startsBlock(index) && paragraph.length < BLOCK_TEXT) {
                    paragraph.append('\n').append(lines[index++])
                }
                addChunks(paragraph.toString()) { MarkdownBlock.Paragraph(it) }
            }
        }
    }
    return MarkdownDocument(blocks, truncated || index < lines.size)
}

/** Fixed delimiter indexes are consumed monotonically, so each nesting level is O(n); depth is capped at five. */
internal fun parseInline(text: String): List<InlineSpan> {
    fun parse(value: String, bold: Boolean, italic: Boolean, link: String?, depth: Int): List<InlineSpan> {
        if (depth > 4 || value.length > 16_384) return listOf(InlineSpan(value, bold, italic, link = link))
        val positions = mutableMapOf<String, MutableList<Int>>()
        var cursor = 0
        while (cursor < value.length) {
            if (markdownEscape(value, cursor)) { cursor += 2; continue }
            listOf("**", "*", "`", "]", ")").forEach { token ->
                if (value.startsWith(token, cursor)) positions.getOrPut(token) { mutableListOf() }.add(cursor)
            }
            cursor++
        }
        val consumed = mutableMapOf<String, Int>()
        fun next(token: String, from: Int): Int {
            val list = positions[token] ?: return -1
            var at = consumed[token] ?: 0
            while (at < list.size && list[at] < from) at++
            consumed[token] = at
            return list.getOrNull(at) ?: -1
        }
        val output = mutableListOf<InlineSpan>()
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { output += InlineSpan(plain.toString(), bold, italic, link = link); plain.clear() } }
        var index = 0
        while (index < value.length && output.size < 2048) {
            val char = value[index]
            if (markdownEscape(value, index)) { plain.append(value[index + 1]); index += 2; continue }
            val token = when {
                char == '`' -> "`"
                value.startsWith("**", index) -> "**"
                char == '*' -> "*"
                else -> null
            }
            if (token != null) {
                val end = next(token, index + token.length)
                if (end > index + token.length) {
                    flush()
                    val inner = value.substring(index + token.length, end)
                    if (token == "`") output += InlineSpan(inner, bold, italic, code = true, link = link)
                    else output += parse(inner, bold || token == "**", italic || token == "*", link, depth + 1)
                    index = end + token.length
                    continue
                }
                // Do not reinterpret one half of an unfinished bold delimiter as italic.
                if (token == "**") { plain.append(token); index += 2; continue }
            }
            if (char == '[' && link == null) {
                val labelEnd = next("]", index + 1)
                if (labelEnd > index && labelEnd + 1 < value.length && value[labelEnd + 1] == '(') {
                    val urlEnd = next(")", labelEnd + 2)
                    if (urlEnd >= labelEnd + 2) {
                        flush()
                        output += parse(value.substring(index + 1, labelEnd), bold, italic, value.substring(labelEnd + 2, urlEnd), depth + 1)
                        index = urlEnd + 1; continue
                    }
                }
            }
            plain.append(char); index++
        }
        if (index < value.length) plain.append(value.substring(index))
        flush()
        return output
    }
    return parse(text, false, false, null, 0)
}
