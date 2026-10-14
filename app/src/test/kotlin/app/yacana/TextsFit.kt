// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Whether the app's texts fit in every language, found from the screenshots' scenes
 * without looking at each one: every text on the screen that ends in an ellipsis, needs more lines
 * than it may take, is wider than its box, is cut by what holds it or goes past the screen's side is written to
 * `build/texts-fit/<language>.tsv`, with its scene and orientation.
 */
internal object TextsFit {
    private const val FOLDER = "build/texts-fit"
    private const val RESOURCES = "../shared/ui/src/commonMain/composeResources"

    /** The app's English texts with no placeholder, by text, to find the same text in another language. */
    private val english: Map<String, String> by lazy {
        strings("en").filterValues { '%' !in it }.entries.associate { (key, text) ->
            text to
                key
        }
    }

    private val languages = mutableMapOf<String, Map<String, String>>()

    private fun strings(language: String): Map<String, String> {
        val folder = if (language == "en") "values" else "values-" + language.replace("-", "-r")
        val file = File("$RESOURCES/$folder/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { i -> (nodes.item(i) as Element).let { it.getAttribute("name") to it.textContent } }
    }

    /** [text] in [language], if it is one of the app's English texts without placeholders; null otherwise. */
    private fun translate(text: String, language: String): String? {
        val key = english[text] ?: return null
        return languages.getOrPut(language) { strings(language) }[key]
    }

    /** [scene] with the texts it taps and waits for in [language]; null when one of them is not a plain text of the app's. */
    fun translated(scene: ScreenshotTest.Scene, language: String): ScreenshotTest.Scene? {
        if (language == "en") return scene
        val texts = (listOfNotNull(scene.click, scene.waitForText, scene.scrollTo, scene.longClick, scene.clickIcon) + scene.clicks).toSet()
        val found = texts.mapNotNull { text -> translate(text, language)?.let { text to it } }.toMap()
        fun one(text: String?) = text?.let(found::getValue)
        return if (found.size < texts.size) {
            null
        } else {
            scene.copy(
                click = one(scene.click),
                waitForText = one(scene.waitForText),
                scrollTo = one(scene.scrollTo),
                longClick = one(scene.longClick),
                clickIcon = one(scene.clickIcon),
                clicks = scene.clicks.map(found::getValue),
            )
        }
    }

    /** A scene that could not be followed in another language, written down to be looked at by hand. */
    fun skipped(case: ScreenshotTest.Case) = write(case, listOf("skipped\t"))

    /** Writes every text of the screen that does not fit. */
    fun check(compose: ComposeContentTestRule, case: ScreenshotTest.Case) {
        val frame = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "frame")).fetchSemanticsNode().boundsInRoot
        val found = mutableListOf<String>()
        compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.firstOrNull() ?: return@forEach
            val text =
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.takeIf { it.isNotBlank() } ?: return@forEach
            val bounds = node.boundsInRoot
            val kind =
                when {
                    (0 until layout.lineCount).any { layout.isLineEllipsized(it) } -> "ellipsis"

                    layout.multiParagraph.didExceedMaxLines -> "lines"

                    // On one line that it does not fit: the rest is lost past its end.
                    layout.lineCount == 1 && tooWide(layout) -> "width"

                    brokenWord(layout, text) -> "broken"

                    bounds.width > 0 && bounds.width + 2 < node.size.width -> "cut"

                    bounds.right > frame.right + 1 -> "outside"

                    else -> return@forEach
                }
            found += "$kind\t${text.replace('\n', ' ')}"
        }
        write(case, found)
    }

    private fun tooWide(layout: TextLayoutResult) = layout.multiParagraph.maxIntrinsicWidth > layout.layoutInput.constraints.maxWidth + 1

    /** Whether a line ends in the middle of a word, as a long word in a narrow box breaks. */
    private fun brokenWord(layout: TextLayoutResult, text: String): Boolean = (0 until layout.lineCount - 1).any { line ->
        val end = layout.getLineEnd(line)
        end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
    }

    private fun write(case: ScreenshotTest.Case, lines: List<String>) {
        if (lines.isEmpty()) return
        val orientation = if (case.landscape) "land" else "port"
        File(FOLDER).mkdirs()
        val file = File("$FOLDER/${case.language}.tsv")
        synchronized(this) { file.appendText(lines.joinToString("") { "${case.scene.name}\t$orientation\t$it\n" }) }
    }
}
