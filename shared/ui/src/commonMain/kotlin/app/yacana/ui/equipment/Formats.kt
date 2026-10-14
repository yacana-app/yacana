// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.equipment

import androidx.compose.runtime.Composable
import app.yacana.alpaca.SwitchInfo
import app.yacana.domain.profile.Location
import app.yacana.ui.format.formatDecimal
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.decimal_separator
import app.yacana.ui.resources.east_short
import app.yacana.ui.resources.north_short
import app.yacana.ui.resources.south_short
import app.yacana.ui.resources.west_short
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/** Decimals take a comma in every language of the app but English (docs/design/STYLE.md), as its texts say. */
@Composable
fun decimalComma(): Boolean = stringResource(Res.string.decimal_separator) == ","

/** A site as `40.4° N · 3.7° W · 650 m`, with the language's decimal separator and compass letters. */
@Composable
fun formatSite(location: Location): String {
    val comma = decimalComma()
    val ns = stringResource(if (location.latitude >= 0) Res.string.north_short else Res.string.south_short)
    val ew = stringResource(if (location.longitude >= 0) Res.string.east_short else Res.string.west_short)
    val latitude = formatDecimal(abs(location.latitude), 1, comma)
    val longitude = formatDecimal(abs(location.longitude), 1, comma)
    return "$latitude° $ns · $longitude° $ew · ${location.elevationM.roundToInt()} m"
}

@Composable
fun formatTemperature(celsius: Double): String = formatDecimal(celsius, 1, decimalComma())

/** A switch value as the device declares it: a percentage for a 0–100 range, else the value with the step's decimals. */
@Composable
fun formatSwitchValue(info: SwitchInfo, value: Double): String = formatSwitchValue(info, value, decimalComma())

fun formatSwitchValue(info: SwitchInfo, value: Double, comma: Boolean): String {
    val decimals = if (info.step >= 1.0 || info.step <= 0.0) 0 else (-log10(info.step)).roundToInt().coerceIn(0, MAX_DECIMALS)
    val number = formatDecimal(value, decimals, comma)
    return if (info.min == 0.0 && info.max == PERCENT) "$number %" else number
}

private const val PERCENT = 100.0
private const val MAX_DECIMALS = 3
