// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import app.yacana.ui.settings.AppLanguage
import app.yacana.ui.settings.LanguageSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The app's language. From Android 13, the system's own per-app language, which Android's
 * settings also show under the app and which turns the screens to it at once; before, the app keeps
 * it and [MainActivity] applies it when it opens, so choosing one opens [activity] again.
 */
class AppLanguages(private val activity: Activity) : LanguageSettings {
    private val mutableChosen = MutableStateFlow(chosen(activity))
    override val chosen: StateFlow<AppLanguage?> = mutableChosen.asStateFlow()

    override val system: AppLanguage
        get() = AppLanguage.of(systemLocale(activity).toLanguageTag()) ?: AppLanguage.English

    override fun choose(language: AppLanguage?) {
        mutableChosen.value = language
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                language?.let { LocaleList.forLanguageTags(it.tag) } ?: LocaleList.getEmptyLocaleList()
        } else {
            prefs(activity).edit { if (language == null) remove(KEY) else putString(KEY, language.tag) }
            activity.recreate()
        }
    }

    companion object {
        private const val PREFS = "language"
        private const val KEY = "chosen"

        /** [base] in the language chosen, before Android 13, where the system does not do it; as it is otherwise. */
        fun applied(base: Context): Context {
            val language = if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {
                null
            } else {
                AppLanguage.of(prefs(base).getString(KEY, null))
            }
            return language?.let {
                val locale = Locale.forLanguageTag(it.tag)
                Locale.setDefault(locale)
                base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) })
            } ?: base
        }

        private fun chosen(context: Context): AppLanguage? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.takeIf {
                !it.isEmpty
            }?.let { AppLanguage.of(it[0].toLanguageTag()) }
        } else {
            AppLanguage.of(prefs(context).getString(KEY, null))
        }

        private fun systemLocale(context: Context): Locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).systemLocales[0]
        } else {
            Resources.getSystem().configuration.locales[0]
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
