// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import app.yacana.ui.settings.AppLanguage
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every text of the app in each of its languages, the shared screens' and
 * Android's own: the same names as in English, each with the same placeholders, none empty, every
 * plural with the quantities its language needs, and apostrophes as each kind of file wants them;
 * and Android's per-app language lists the same languages.
 */
class TranslationsTest {
    private val translated = AppLanguage.entries.filter { it != AppLanguage.English }

    @Test
    fun everyTextIsInEveryLanguage() {
        listOf(SHARED, ANDROID).forEach { resources ->
            val english = texts(resources, "values")
            translated.forEach { language ->
                val texts = texts(resources, language.folder)
                assertEquals(emptySet(), english.keys - texts.keys, "Not in ${language.tag}, $resources")
                assertEquals(emptySet(), texts.keys - english.keys, "In ${language.tag} only, $resources")
            }
        }
    }

    @Test
    fun eachTextHasTheSamePlaceholdersInEveryLanguage() {
        listOf(SHARED, ANDROID).forEach { resources ->
            val english = texts(resources, "values")
            translated.forEach { language ->
                val texts = texts(resources, language.folder)
                val different =
                    english.keys.intersect(texts.keys).filter { name ->
                        english.getValue(name).map(::placeholders).toSet() != texts.getValue(name).map(::placeholders).toSet()
                    }
                assertEquals(emptyList(), different, "Placeholders that differ in ${language.tag}, $resources")
            }
        }
    }

    @Test
    fun noTextIsEmpty() {
        val empty =
            AppLanguage.entries.flatMap { language ->
                texts(SHARED, folder(language)).filter { (_, texts) -> texts.any { it.isBlank() } }.keys.map { "${language.tag}:$it" }
            }
        assertEquals(emptyList(), empty)
    }

    @Test
    fun noSharedTextShowsAnEscapedApostrophe() {
        // Compose's resources show a text as it is written: an Android-style \' would show its backslash.
        val escaped =
            AppLanguage.entries.flatMap { language ->
                texts(SHARED, folder(language)).filter { (_, texts) -> texts.any { "\\'" in it } }.keys.map { "${language.tag}:$it" }
            }
        assertEquals(emptyList(), escaped)
    }

    @Test
    fun androidsApostrophesAreEscaped() {
        // Android's own resources need \': a bare one ends the text there.
        val bare =
            AppLanguage.entries.flatMap { language ->
                File("$ANDROID/${folder(language)}/strings.xml").readLines().filter { BARE.containsMatchIn(it.replace(TAG, "")) }
                    .map { "${language.tag}: ${it.trim()}" }
            }
        assertEquals(emptyList(), bare)
    }

    @Test
    fun pluralsHaveTheQuantitiesTheirLanguageNeeds() {
        AppLanguage.entries.forEach { language ->
            val plurals = plurals(folder(language))
            assertTrue(plurals.isNotEmpty(), language.tag)
            // Spanish and its neighbours have a form for large numbers, as "1 millón de estrellas".
            val needed = if (language in MANY) setOf("one", "many", "other") else setOf("one", "other")
            plurals.forEach { (name, quantities) -> assertTrue(quantities.containsAll(needed), "${language.tag}: $name") }
        }
    }

    @Test
    fun androidsPerAppLanguageListsTheAppsLanguages() {
        val config = File("$ANDROID/xml/locales_config.xml").readText()
        val listed = Regex("""android:name="([a-zA-Z-]+)"""").findAll(config).map { it.groupValues[1] }.toSet()
        assertEquals(AppLanguage.entries.map { it.tag }.toSet(), listed)
    }

    private fun folder(language: AppLanguage) = if (language == AppLanguage.English) "values" else language.folder

    /**
     * Each string's text, each list's items and each plural's texts, by name, from [resources] in
     * [folder]; none that is not translated.
     */
    private fun texts(resources: String, folder: String): Map<String, List<String>> {
        val root = document(resources, folder)
        val strings = root.elements("string").filter { it.getAttribute("translatable") != "false" }
            .associate { it.getAttribute("name") to listOf(it.textContent) }
        val lists = root.elements("string-array").associate { list ->
            list.getAttribute("name") to
                list.elements("item").map { it.textContent }
        }
        val plurals = root.elements("plurals").associate { plural ->
            plural.getAttribute("name") to
                plural.elements("item").map { it.textContent }
        }
        return strings + lists + plurals
    }

    private fun plurals(folder: String): Map<String, Set<String>> = document(SHARED, folder).elements("plurals").associate { plural ->
        plural.getAttribute("name") to plural.elements("item").map { it.getAttribute("quantity") }.toSet()
    }

    private fun document(resources: String, folder: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("$resources/$folder/strings.xml")).documentElement

    private fun Element.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** The placeholders of a text, as "%1$s", in order. */
    private fun placeholders(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.value }.sorted().toList()

    private companion object {
        /** The shared module's resources, from the app module's folder, where the tests run, and the app's own. */
        const val SHARED = "../shared/ui/src/commonMain/composeResources"
        const val ANDROID = "src/main/res"

        val PLACEHOLDER = Regex("%\\d+\\$[sd]")
        val BARE = Regex("""(?<!\\)'""")
        val TAG = Regex("<[^>]+>")

        /** The languages whose plurals have "many", as CLDR gives them. */
        val MANY =
            setOf(
                AppLanguage.Spanish,
                AppLanguage.Catalan,
                AppLanguage.French,
                AppLanguage.Italian,
                AppLanguage.Portuguese,
                AppLanguage.BrazilianPortuguese,
            )
    }
}
