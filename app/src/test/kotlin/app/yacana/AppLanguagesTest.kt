// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import android.app.Activity
import android.app.LocaleManager
import android.os.LocaleList
import app.yacana.ui.settings.AppLanguage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app's language on Android: the system's per-app language, which Android's settings show too. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppLanguagesTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val locales = activity.getSystemService(LocaleManager::class.java)

    @Test
    fun aLanguageChosenIsTheAppsInAndroid() {
        val languages = AppLanguages(activity)

        languages.choose(AppLanguage.Basque)

        assertEquals(AppLanguage.Basque, languages.chosen.value)
        assertEquals("eu", locales.applicationLocales[0].language)
        assertEquals(AppLanguage.Basque, AppLanguages(activity).chosen.value, "read back the next time")
    }

    @Test
    fun theSystemsAgainLeavesNoneOfItsOwn() {
        val languages = AppLanguages(activity)
        languages.choose(AppLanguage.French)

        languages.choose(null)

        assertNull(languages.chosen.value)
        assertTrue(locales.applicationLocales.isEmpty)
    }

    @Test
    fun theSystemsLanguageIsThePhonesWhenTheAppHasIt() {
        // Robolectric's phone speaks American English.
        assertEquals(AppLanguage.English, AppLanguages(activity).system)
        assertEquals(AppLanguage.French, AppLanguage.of(LocaleList.forLanguageTags("fr-CA")[0].toLanguageTag()))
        assertEquals(AppLanguage.Portuguese, AppLanguage.of("pt-PT"))
        assertEquals(AppLanguage.Portuguese, AppLanguage.of("pt-AO"), "Portugal's for any Portuguese but Brazil's")
        assertEquals(AppLanguage.BrazilianPortuguese, AppLanguage.of("pt-BR"))
        assertEquals(AppLanguage.BrazilianPortuguese, AppLanguage.of("pt_BR"))
        assertNull(AppLanguage.of("ja"), "a language the app does not have")
    }

    @Test
    fun brazilianPortugueseIsChosenByItsRegion() {
        AppLanguages(activity).choose(AppLanguage.BrazilianPortuguese)

        assertEquals("pt-BR", locales.applicationLocales[0].toLanguageTag())
        assertEquals(AppLanguage.BrazilianPortuguese, AppLanguages(activity).chosen.value)
    }
}
