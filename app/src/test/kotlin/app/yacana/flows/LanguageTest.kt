// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import app.yacana.ui.Section
import app.yacana.ui.navigation.Screen
import app.yacana.ui.settings.AppLanguage
import app.yacana.ui.settings.LanguageSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Settings · language" as it is touched: from Settings, a language chosen, and the system's again. */
class LanguageTest : FlowTest() {
    /** The platform's choice, kept here: the screens follow it as they would the phone's. */
    private val languages =
        object : LanguageSettings {
            override val chosen = MutableStateFlow<AppLanguage?>(null)
            override val system = AppLanguage.Spanish

            override fun choose(language: AppLanguage?) {
                chosen.value = language
            }
        }

    @Test
    fun aLanguageIsChosenAndTheSystemsAgain() {
        show(Section.Night, Screen.Settings, languages = languages)
        assertTrue(appears("Same as the system · Español"))

        tap("Language")
        assertTrue(appears("Now: Español"))
        tap("Français")

        assertEquals(AppLanguage.French, languages.chosen.value)
        tap("Same as the system")
        assertNull(languages.chosen.value)
    }

    @Test
    fun spainsLanguagesComeFirstAndEachSaysItsNameHere() {
        show(Section.Night, Screen.Settings, Screen.Language, languages = languages)

        // One list, Spain's four together first, each in its own language and in the app's.
        listOf("Español", "Català", "Euskara", "Galego", "Deutsch", "English", "Français", "Italiano", "Português (Portugal)").forEach {
            assertTrue(appears(it), it)
        }
        assertTrue(appears("Basque"))
        assertTrue(appears("Portuguese (Portugal)"))
        assertTrue(appears("Português (Brasil)"))
        assertTrue(appears("Portuguese (Brazil)"))
    }
}
