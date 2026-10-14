// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The languages the app speaks, by their BCP 47 tag and the name each gives itself,
 * in the order of "Settings · language": Spain's four together first, then the others. Portuguese is
 * Portugal's and Brazil's, each with its own texts.
 */
enum class AppLanguage(val tag: String, val endonym: String) {
    Spanish("es", "Español"),
    Catalan("ca", "Català"),
    Basque("eu", "Euskara"),
    Galician("gl", "Galego"),
    German("de", "Deutsch"),
    English("en", "English"),
    French("fr", "Français"),
    Italian("it", "Italiano"),
    Portuguese("pt", "Português (Portugal)"),
    BrazilianPortuguese("pt-BR", "Português (Brasil)"),
    ;

    /** The folder of its resources, as Android and Compose name it: `values-pt-rBR`, `values-fr`. */
    val folder: String get() = "values-" + tag.replace("-", "-r")

    companion object {
        /**
         * The language of [tag], as a locale names it ("fr", "fr-CA", "pt-BR"): the one of its
         * language and region, else of its language; null if the app does not have it.
         */
        fun of(tag: String?): AppLanguage? {
            val parts = tag?.replace('_', '-')?.split('-') ?: return null
            val language = parts.first().lowercase()
            val region = parts.drop(1).firstOrNull { it.length == 2 }?.uppercase()
            return entries.firstOrNull { it.tag == "$language-$region" } ?: entries.firstOrNull { it.tag == language }
        }
    }
}

/**
 * Which language the app speaks: the system's, as at first, or one chosen. The platform
 * keeps the choice and turns the screens to it at once.
 */
interface LanguageSettings {
    /** The language chosen; null while the app follows the system. */
    val chosen: StateFlow<AppLanguage?>

    /** What following the system gives now: the phone's language if the app has it, else English. */
    val system: AppLanguage

    fun choose(language: AppLanguage?)
}

/** The system's language, unchangeable, where the platform cannot change it, as in screenshots. */
class SystemLanguage(override val system: AppLanguage = AppLanguage.English) : LanguageSettings {
    override val chosen: StateFlow<AppLanguage?> = MutableStateFlow(null)

    override fun choose(language: AppLanguage?) = Unit
}
