package io.github.kobi2187.readaloud.web

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Turns an HTML subtree into paragraphs of plain text. */
object HtmlText {
    private val blockTags = setOf(
        "p", "div", "section", "article", "li", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "pre", "tr", "dd", "dt", "figcaption", "header", "footer", "main", "ul", "ol", "table",
    )

    fun blocks(root: Element): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            val t = current.toString().replace(Regex("[ \\t\\u00A0]+"), " ").trim()
            if (t.isNotEmpty()) out += t
            current.clear()
        }
        fun walk(node: Node) {
            when (node) {
                is TextNode -> current.append(node.wholeText)
                is Element -> {
                    val tag = node.tagName().lowercase()
                    if (tag in setOf("script", "style", "noscript", "iframe", "svg", "button", "form")) return
                    if (tag == "br") { flush(); return }
                    val block = tag in blockTags
                    if (block) flush()
                    node.childNodes().forEach(::walk)
                    if (block) flush()
                }
                else -> Unit
            }
        }
        walk(root)
        flush()
        return out
    }
}
