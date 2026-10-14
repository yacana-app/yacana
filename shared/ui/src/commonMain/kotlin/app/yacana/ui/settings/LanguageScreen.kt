// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.yacana.ui.YacanaIcons
import app.yacana.ui.design.DeviceRow
import app.yacana.ui.design.SectionCard
import app.yacana.ui.design.Touch
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.language_ca
import app.yacana.ui.resources.language_choose
import app.yacana.ui.resources.language_de
import app.yacana.ui.resources.language_en
import app.yacana.ui.resources.language_es
import app.yacana.ui.resources.language_eu
import app.yacana.ui.resources.language_fr
import app.yacana.ui.resources.language_gl
import app.yacana.ui.resources.language_hint
import app.yacana.ui.resources.language_it
import app.yacana.ui.resources.language_now
import app.yacana.ui.resources.language_pt
import app.yacana.ui.resources.language_pt_br
import app.yacana.ui.resources.language_system
import app.yacana.ui.theme.LocalPhotoFilter
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * "Settings · language": the system's language, with what it gives now, or each of the app's
 * with its flag, in its own language and under it in the app's when it is said otherwise, in one
 * list with Spain's together first. The one in use bears the mark, and a tap changes the app at once.
 */
@Composable
fun LanguageScreen(languages: LanguageSettings) {
    val chosen by languages.chosen.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            DeviceRow(
                stringResource(Res.string.language_system),
                stringResource(Res.string.language_now, languages.system.endonym),
                YacanaIcons.DeviceMobile,
                last = true,
                onClick = { languages.choose(null) },
            ) { if (chosen == null) Mark() }
        }
        Muted(stringResource(Res.string.language_choose))
        SectionCard {
            AppLanguage.entries.forEachIndexed { index, language ->
                LanguageRow(language, chosen == language, last = index == AppLanguage.entries.lastIndex) { languages.choose(language) }
            }
        }
        Muted(stringResource(Res.string.language_hint))
    }
}

/** A language with its flag and its own name, and under it its name in the app's language when it is another. */
@Composable
private fun LanguageRow(language: AppLanguage, selected: Boolean, last: Boolean, onClick: () -> Unit) {
    val here = stringResource(language.nameHere)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Touch.Min)
                .clickable(role = Role.RadioButton, onClick = onClick)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Flag(language)
            Column(Modifier.weight(1f)) {
                Text(language.endonym, style = MaterialTheme.typography.bodyLarge)
                if (!here.equals(language.endonym, ignoreCase = true)) {
                    Text(here, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selected) Mark()
        }
        if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun Mark() {
    Icon(YacanaIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Muted(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

/** The language's name in the app's language, as "French". */
private val AppLanguage.nameHere: StringResource
    get() =
        when (this) {
            AppLanguage.Spanish -> Res.string.language_es
            AppLanguage.Catalan -> Res.string.language_ca
            AppLanguage.Basque -> Res.string.language_eu
            AppLanguage.Galician -> Res.string.language_gl
            AppLanguage.German -> Res.string.language_de
            AppLanguage.English -> Res.string.language_en
            AppLanguage.French -> Res.string.language_fr
            AppLanguage.Italian -> Res.string.language_it
            AppLanguage.Portuguese -> Res.string.language_pt
            AppLanguage.BrazilianPortuguese -> Res.string.language_pt_br
        }

/**
 * The language's flag, simplified as the design draws it, 3 by 2: Spain's and Galicia's without
 * their arms, the Union Jack for English, and Portugal's and Brazil's for theirs. In red mode it goes red,
 * as every photo of the app.
 */
@Composable
internal fun Flag(language: AppLanguage) {
    val filter = LocalPhotoFilter.current
    Canvas(Modifier.size(FLAG_WIDTH, FLAG_HEIGHT)) {
        when (language) {
            AppLanguage.Spanish -> stripes(filter, listOf(SPAIN_RED to 1f, SPAIN_GOLD to 2f, SPAIN_RED to 1f))

            AppLanguage.Catalan -> stripes(filter, (0 until SENYERA).map { (if (it % 2 == 0) CATALAN_GOLD else CATALAN_RED) to 1f })

            AppLanguage.Basque -> {
                drawRect(BASQUE_RED, colorFilter = filter)
                saltire(BASQUE_GREEN, size.height * BASQUE_SALTIRE, filter)
                cross(Color.White, size.height * BASQUE_CROSS, filter)
            }

            AppLanguage.Galician -> {
                drawRect(Color.White, colorFilter = filter)
                drawLine(GALICIAN_BLUE, Offset.Zero, Offset(size.width, size.height), size.height * GALICIAN_BAND, colorFilter = filter)
            }

            AppLanguage.German -> stripes(filter, listOf(Color.Black to 1f, GERMAN_RED to 1f, GERMAN_GOLD to 1f))

            AppLanguage.English -> {
                drawRect(UNION_BLUE, colorFilter = filter)
                saltire(Color.White, size.height * UNION_WHITE_SALTIRE, filter)
                saltire(UNION_RED, size.height * UNION_RED_SALTIRE, filter)
                cross(Color.White, size.height * UNION_WHITE_CROSS, filter)
                cross(UNION_RED, size.height * UNION_RED_CROSS, filter)
            }

            AppLanguage.French -> bands(filter, listOf(FRENCH_BLUE, Color.White, FRENCH_RED))

            AppLanguage.Italian -> bands(filter, listOf(ITALIAN_GREEN, Color.White, ITALIAN_RED))

            AppLanguage.Portuguese -> {
                drawRect(PORTUGUESE_RED, colorFilter = filter)
                drawRect(PORTUGUESE_GREEN, size = Size(size.width * PORTUGUESE_GREEN_SHARE, size.height), colorFilter = filter)
                val middle = Offset(size.width * PORTUGUESE_GREEN_SHARE, size.height / 2)
                drawCircle(PORTUGUESE_GOLD, size.height * PORTUGUESE_SPHERE, middle, colorFilter = filter)
                drawCircle(PORTUGUESE_RED, size.height * PORTUGUESE_SHIELD, middle, colorFilter = filter)
            }

            AppLanguage.BrazilianPortuguese -> {
                drawRect(BRAZIL_GREEN, colorFilter = filter)
                val inset = size.height * BRAZIL_INSET
                val rhombus =
                    Path().apply {
                        moveTo(size.width / 2, inset)
                        lineTo(size.width - inset, size.height / 2)
                        lineTo(size.width / 2, size.height - inset)
                        lineTo(inset, size.height / 2)
                        close()
                    }
                drawPath(rhombus, BRAZIL_GOLD, colorFilter = filter)
                drawCircle(BRAZIL_BLUE, size.height * BRAZIL_GLOBE, center, colorFilter = filter)
            }
        }
        // A faint edge, so that white and dark flags show their shape on the card.
        drawRoundRect(
            Color.White.copy(alpha = EDGE_ALPHA),
            style = Stroke(1.dp.toPx()),
            cornerRadius = CornerRadius(2.dp.toPx()),
            colorFilter = filter,
        )
    }
}

/** Horizontal stripes, top to bottom, each as tall as its share. */
private fun DrawScope.stripes(filter: ColorFilter?, colors: List<Pair<Color, Float>>) {
    val total = colors.sumOf { it.second.toDouble() }.toFloat()
    var top = 0f
    colors.forEach { (color, share) ->
        val height = size.height * share / total
        drawRect(color, Offset(0f, top), Size(size.width, height), colorFilter = filter)
        top += height
    }
}

/** Vertical bands of the same width, left to right. */
private fun DrawScope.bands(filter: ColorFilter?, colors: List<Color>) {
    val width = size.width / colors.size
    colors.forEachIndexed { index, color -> drawRect(color, Offset(width * index, 0f), Size(width, size.height), colorFilter = filter) }
}

private fun DrawScope.saltire(color: Color, width: Float, filter: ColorFilter?) {
    drawLine(color, Offset.Zero, Offset(size.width, size.height), width, colorFilter = filter)
    drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), width, colorFilter = filter)
}

private fun DrawScope.cross(color: Color, width: Float, filter: ColorFilter?) {
    drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), width, colorFilter = filter)
    drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), width, colorFilter = filter)
}

private val FLAG_WIDTH = 24.dp
private val FLAG_HEIGHT = 16.dp
private const val EDGE_ALPHA = 0.18f

private val SPAIN_RED = Color(0xFFAA151B)
private val SPAIN_GOLD = Color(0xFFF1BF00)
private val CATALAN_GOLD = Color(0xFFFCDD09)
private val CATALAN_RED = Color(0xFFDA121A)

/** The senyera's nine stripes, four of them red. */
private const val SENYERA = 9
private val BASQUE_RED = Color(0xFFD52B1E)
private val BASQUE_GREEN = Color(0xFF009B48)
private const val BASQUE_SALTIRE = 0.16f
private const val BASQUE_CROSS = 0.15f
private val GALICIAN_BLUE = Color(0xFF009AD8)
private const val GALICIAN_BAND = 0.26f
private val GERMAN_RED = Color(0xFFDD0000)
private val GERMAN_GOLD = Color(0xFFFFCE00)
private val UNION_BLUE = Color(0xFF012169)
private val UNION_RED = Color(0xFFC8102E)
private const val UNION_WHITE_SALTIRE = 0.2f
private const val UNION_RED_SALTIRE = 0.07f
private const val UNION_WHITE_CROSS = 0.31f
private const val UNION_RED_CROSS = 0.19f
private val FRENCH_BLUE = Color(0xFF0055A4)
private val FRENCH_RED = Color(0xFFEF4135)
private val ITALIAN_GREEN = Color(0xFF009246)
private val ITALIAN_RED = Color(0xFFCE2B37)
private val PORTUGUESE_RED = Color(0xFFFF0000)
private val BRAZIL_GREEN = Color(0xFF009C3B)
private val BRAZIL_GOLD = Color(0xFFFFDF00)
private val BRAZIL_BLUE = Color(0xFF002776)
private const val BRAZIL_INSET = 0.12f
private const val BRAZIL_GLOBE = 0.22f
private val PORTUGUESE_GREEN = Color(0xFF006600)
private val PORTUGUESE_GOLD = Color(0xFFFFCC00)
private const val PORTUGUESE_GREEN_SHARE = 0.4f
private const val PORTUGUESE_SPHERE = 0.21f
private const val PORTUGUESE_SHIELD = 0.12f
