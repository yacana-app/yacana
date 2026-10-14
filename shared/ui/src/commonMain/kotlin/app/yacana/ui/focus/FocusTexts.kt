// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.focus

import androidx.compose.runtime.Composable
import app.yacana.domain.focus.AutofocusResult
import app.yacana.domain.focus.FocusFitting
import app.yacana.domain.focus.HyperbolicFitting
import app.yacana.domain.focus.QuadraticFitting
import app.yacana.domain.focus.TrendlineFitting
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.af_fit_hyperbolic
import app.yacana.ui.resources.af_fit_in_hyperbolic
import app.yacana.ui.resources.af_fit_in_parabolic
import app.yacana.ui.resources.af_fit_in_trend_hyperbolic
import app.yacana.ui.resources.af_fit_in_trend_parabolic
import app.yacana.ui.resources.af_fit_in_trendlines
import app.yacana.ui.resources.af_fit_parabolic
import app.yacana.ui.resources.af_fit_trend_hyperbolic
import app.yacana.ui.resources.af_fit_trend_parabolic
import app.yacana.ui.resources.af_fit_trendlines
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration

/** A fitting's name, as Settings shows it. */
@Composable
internal fun fittingName(fitting: FocusFitting): String = stringResource(fittingLabel(fitting))

internal fun fittingLabel(fitting: FocusFitting): StringResource = when (fitting) {
    FocusFitting.Trendlines -> Res.string.af_fit_trendlines
    FocusFitting.Parabolic -> Res.string.af_fit_parabolic
    FocusFitting.TrendParabolic -> Res.string.af_fit_trend_parabolic
    FocusFitting.Hyperbolic -> Res.string.af_fit_hyperbolic
    FocusFitting.TrendHyperbolic -> Res.string.af_fit_trend_hyperbolic
}

/** A fitting's name as it reads after "Fitting", such as "Hyperbolic" or "Trend line". */
@Composable
internal fun fittingInSentence(fitting: FocusFitting): String = stringResource(
    when (fitting) {
        FocusFitting.Trendlines -> Res.string.af_fit_in_trendlines
        FocusFitting.Parabolic -> Res.string.af_fit_in_parabolic
        FocusFitting.TrendParabolic -> Res.string.af_fit_in_trend_parabolic
        FocusFitting.Hyperbolic -> Res.string.af_fit_in_hyperbolic
        FocusFitting.TrendHyperbolic -> Res.string.af_fit_in_trend_hyperbolic
    },
)

/** How well the result's fitting explains its points. */
internal fun rSquared(result: AutofocusResult): Double = when (result.fitting) {
    FocusFitting.Hyperbolic, FocusFitting.TrendHyperbolic -> HyperbolicFitting(result.points).rSquared
    FocusFitting.Parabolic, FocusFitting.TrendParabolic -> QuadraticFitting(result.points).rSquared
    FocusFitting.Trendlines -> TrendlineFitting(result.points).let { minOf(it.left.rSquared, it.right.rSquared) }
}

/** "1 min 12 s", "2 min" on the minute, or "48 s" under a minute. */
internal fun formatDuration(duration: Duration): String {
    val seconds = duration.inWholeSeconds
    val minutes = seconds / SECONDS_PER_MINUTE
    val rest = seconds % SECONDS_PER_MINUTE
    return when {
        minutes == 0L -> "$seconds s"
        rest == 0L -> "$minutes min"
        else -> "$minutes min $rest s"
    }
}

private const val SECONDS_PER_MINUTE = 60
