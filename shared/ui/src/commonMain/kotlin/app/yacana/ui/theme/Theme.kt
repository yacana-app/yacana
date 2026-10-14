// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFE8B04A),
        onPrimary = Color(0xFF2B1D03),
        primaryContainer = Color(0xFF3A2C10),
        onPrimaryContainer = Color(0xFFE8B04A),
        secondary = Color(0xFFE8B04A),
        onSecondary = Color(0xFF2B1D03),
        // The designs' accent fill: the selected tab, selected chips and filled buttons.
        secondaryContainer = Color(0xFFE8B04A),
        onSecondaryContainer = Color(0xFF2B1D03),
        tertiary = Color(0xFF5DCAA5),
        onTertiary = Color(0xFF0B0F14),
        background = Color(0xFF0B0F14),
        onBackground = Color(0xFFE8ECF1),
        surface = Color(0xFF0B0F14),
        onSurface = Color(0xFFE8ECF1),
        surfaceVariant = Color(0xFF1F2733),
        onSurfaceVariant = Color(0xFF98A4B4),
        surfaceContainerLowest = Color(0xFF04060A),
        surfaceContainerLow = Color(0xFF10151C),
        surfaceContainer = Color(0xFF151B24),
        surfaceContainerHigh = Color(0xFF1F2733),
        surfaceContainerHighest = Color(0xFF262D37),
        outline = Color(0xFF2C3644),
        outlineVariant = Color(0xFF2C3644),
        error = Color(0xFFF07A7A),
        onError = Color(0xFF0B0F14),
    )

/** Red on black only: no color in this scheme has a green or blue component. */
private val RedColors =
    darkColorScheme(
        primary = Color(0xFFFF0000),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF4A0000),
        onPrimaryContainer = Color(0xFFFF0000),
        inversePrimary = Color(0xFF4A0000),
        secondary = Color(0xFFFF0000),
        onSecondary = Color(0xFF000000),
        // The designs' accent fill: dark red, so that a filled button does not light up the night.
        secondaryContainer = Color(0xFF4A0000),
        onSecondaryContainer = Color(0xFFFF0000),
        tertiary = Color(0xFFE00000),
        onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF4A0000),
        onTertiaryContainer = Color(0xFFFF0000),
        background = Color(0xFF000000),
        onBackground = Color(0xFFE00000),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFE00000),
        surfaceVariant = Color(0xFF240000),
        onSurfaceVariant = Color(0xFFA80000),
        surfaceTint = Color(0xFFFF0000),
        inverseSurface = Color(0xFFE00000),
        inverseOnSurface = Color(0xFF000000),
        error = Color(0xFFE00000),
        onError = Color(0xFF000000),
        errorContainer = Color(0xFF4A0000),
        onErrorContainer = Color(0xFFFF0000),
        outline = Color(0xFF3D0000),
        outlineVariant = Color(0xFF3D0000),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF240000),
        surfaceContainer = Color(0xFF140000),
        surfaceContainerHigh = Color(0xFF240000),
        surfaceContainerHighest = Color(0xFF2E0000),
        surfaceContainerLow = Color(0xFF0A0000),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceDim = Color(0xFF000000),
    )

fun colorSchemeFor(mode: YacanaThemeMode): ColorScheme = when (mode) {
    YacanaThemeMode.Dark -> DarkColors
    YacanaThemeMode.Red -> RedColors
}

/** Fixed-width digits everywhere, so numbers do not jump as they change. */
private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

private val YacanaTypography =
    Typography().run {
        copy(
            displayLarge = displayLarge.tabular(),
            displayMedium = displayMedium.tabular(),
            displaySmall = displaySmall.tabular(),
            headlineLarge = headlineLarge.tabular(),
            headlineMedium = headlineMedium.tabular(),
            headlineSmall = headlineSmall.tabular(),
            titleLarge = titleLarge.tabular(),
            titleMedium = titleMedium.tabular(),
            titleSmall = titleSmall.tabular(),
            bodyLarge = bodyLarge.tabular(),
            bodyMedium = bodyMedium.tabular(),
            bodySmall = bodySmall.tabular(),
            labelLarge = labelLarge.tabular(),
            labelMedium = labelMedium.tabular(),
            labelSmall = labelSmall.tabular(),
        )
    }

@Composable
fun YacanaTheme(mode: YacanaThemeMode, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalPhotoFilter provides if (mode == YacanaThemeMode.Red) RedPhotoFilter else null,
        LocalRedMode provides (mode == YacanaThemeMode.Red),
        LocalTelescopeColor provides if (mode == YacanaThemeMode.Red) TELESCOPE_RED else TELESCOPE_BLUE,
    ) {
        MaterialTheme(
            colorScheme = colorSchemeFor(mode),
            typography = YacanaTypography,
            content = content,
        )
    }
}
