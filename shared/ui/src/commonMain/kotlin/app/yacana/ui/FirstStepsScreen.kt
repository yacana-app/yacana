// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.yacana.ui.design.AccentButton
import app.yacana.ui.equipment.FindEquipment
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.eq_find_hint
import app.yacana.ui.resources.eq_find_title
import app.yacana.ui.resources.field_intro
import app.yacana.ui.resources.field_start
import app.yacana.ui.resources.field_title
import app.yacana.ui.resources.first_steps_continue
import app.yacana.ui.resources.first_steps_permissions
import app.yacana.ui.resources.first_steps_skip
import app.yacana.ui.resources.permissions_intro
import app.yacana.ui.settings.PermissionsList
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The first launch, as "First steps": the permissions explained; the equipment, which the
 * Seestar fills in with its own profile; and what the night needs without internet
 * ("offline"). Each step has a large title, the dots of the steps and its action at the bottom.
 */
@Composable
fun FirstStepsScreen(services: AppServices, startAt: Int = 0) {
    var step by rememberSaveable { mutableIntStateOf(startAt) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (step) {
                0 -> {
                    Header(Res.string.first_steps_permissions, Res.string.permissions_intro)
                    PermissionsList(services.permissions, intro = false)
                }

                1 -> {
                    Header(Res.string.eq_find_title, Res.string.eq_find_hint)
                    FindEquipment(services.equipment, onUsed = {
                        services.equipment.connect()
                        step = 2
                    })
                }

                else -> {
                    Header(Res.string.field_title, Res.string.field_intro)
                    ReadyForTheField(services)
                }
            }
        }
        Dots(count = STEPS, current = step)
        when (step) {
            0 -> AccentButton(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(stringResource(Res.string.first_steps_continue))
            }

            1 -> TextButton(
                onClick = { step = 2 },
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp),
            ) {
                Text(stringResource(Res.string.first_steps_skip), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            else -> AccentButton(
                onClick = services.firstSteps::finish,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            ) { Text(stringResource(Res.string.field_start)) }
        }
    }
}

@Composable
private fun Header(title: StringResource, intro: StringResource) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The design's dots under each step: the current one long, in the accent colour. */
@Composable
private fun Dots(count: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count) { index ->
            val on = index == current
            Box(
                Modifier
                    .size(width = if (on) 20.dp else 8.dp, height = 8.dp)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
            )
        }
    }
}

/** The steps: permissions, equipment, and ready for the field. */
private const val STEPS = 3
