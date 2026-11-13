package com.shihab.diplay.update

/**
 * Release bodies are GitHub Markdown, but the About page shows plain text. Keep the words and drop
 * the syntax: headings, emphasis, code marks and link targets. Lists become bullets.
 */
internal object UpdateNotes {
    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$")
    private val LIST_ITEM = Regex("^(\\s*)[-*+]\\s+")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val STRONG = Regex("(\\*\\*|__)(.+?)\\1")
    private val EMPHASIS = Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])")
    private val CODE = Regex("`([^`]*)`")
    private val BLANK_RUN = Regex("\n{3,}")

    fun plain(markdown: String): String = markdown
        .replace("\r\n", "\n")
        .lines()
        .joinToString("\n") { line ->
            val text = HEADING.matchEntire(line)?.groupValues?.get(1)
                ?: LIST_ITEM.replace(line) { "${it.groupValues[1]}• " }
            text.replace(IMAGE, "$1")
                .replace(LINK, "$1")
                .replace(CODE, "$1")
                .replace(STRONG, "$2")
                .replace(EMPHASIS, "$1")
        }
        .replace(BLANK_RUN, "\n\n")
        .trim()
}
