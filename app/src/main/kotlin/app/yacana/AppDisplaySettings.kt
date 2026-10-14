// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import android.content.Context
import androidx.core.content.edit
import app.yacana.ui.DisplaySettings
import app.yacana.ui.theme.YacanaThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The display options, saved so that the app opens in red mode if it was left in it. */
class AppDisplaySettings(context: Context) : DisplaySettings {
    private val prefs = context.getSharedPreferences("display", Context.MODE_PRIVATE)
    private val mode =
        MutableStateFlow(YacanaThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) } ?: YacanaThemeMode.Dark)
    private val brightness = MutableStateFlow(prefs.getFloat(KEY_RED_BRIGHTNESS, DEFAULT_RED_BRIGHTNESS))
    private val marks = MutableStateFlow(prefs.getBoolean(KEY_MARK_STARS, false))
    private val marked = MutableStateFlow(prefs.getInt(KEY_MARKED_STARS, DisplaySettings.DEFAULT_MARKED_STARS))
    private val stack = MutableStateFlow(prefs.getBoolean(KEY_SHOW_STACK, true))

    override val themeMode: StateFlow<YacanaThemeMode> = mode.asStateFlow()
    override val redBrightness: StateFlow<Float> = brightness.asStateFlow()
    override val markStars: StateFlow<Boolean> = marks.asStateFlow()
    override val markedStars: StateFlow<Int> = marked.asStateFlow()
    override val showStack: StateFlow<Boolean> = stack.asStateFlow()

    override fun setThemeMode(mode: YacanaThemeMode) {
        this.mode.value = mode
        prefs.edit { putString(KEY_MODE, mode.name) }
    }

    override fun setRedBrightness(value: Float) {
        brightness.value = value.coerceIn(DisplaySettings.MIN_RED_BRIGHTNESS, 1f)
        prefs.edit { putFloat(KEY_RED_BRIGHTNESS, brightness.value) }
    }

    override fun setMarkStars(on: Boolean) {
        marks.value = on
        prefs.edit { putBoolean(KEY_MARK_STARS, on) }
    }

    override fun setMarkedStars(count: Int) {
        marked.value = count.coerceIn(DisplaySettings.MARKED_STARS)
        prefs.edit { putInt(KEY_MARKED_STARS, marked.value) }
    }

    override fun setShowStack(on: Boolean) {
        stack.value = on
        prefs.edit { putBoolean(KEY_SHOW_STACK, on) }
    }

    private companion object {
        const val KEY_MODE = "themeMode"
        const val KEY_RED_BRIGHTNESS = "redBrightness"
        const val KEY_MARK_STARS = "markStars"
        const val KEY_MARKED_STARS = "markedStars"
        const val KEY_SHOW_STACK = "showStack"

        /** Dim, for eyes adapted to the dark. */
        const val DEFAULT_RED_BRIGHTNESS = 0.1f
    }
}
