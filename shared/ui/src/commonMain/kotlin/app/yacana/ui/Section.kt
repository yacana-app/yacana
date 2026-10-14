// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui

import androidx.compose.ui.graphics.vector.ImageVector
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.purpose_equipment
import app.yacana.ui.resources.purpose_imaging
import app.yacana.ui.resources.purpose_night
import app.yacana.ui.resources.purpose_sequence
import app.yacana.ui.resources.purpose_sky
import app.yacana.ui.resources.section_equipment
import app.yacana.ui.resources.section_imaging
import app.yacana.ui.resources.section_night
import app.yacana.ui.resources.section_sequence
import app.yacana.ui.resources.section_sky
import org.jetbrains.compose.resources.StringResource

/** The five sections of the app, and the phase that brought each one. */
enum class Section(val title: StringResource, val purpose: StringResource, val icon: ImageVector, val phase: Int) {
    Night(Res.string.section_night, Res.string.purpose_night, YacanaIcons.Night, phase = 2),
    Equipment(Res.string.section_equipment, Res.string.purpose_equipment, YacanaIcons.Equipment, phase = 1),
    Sky(Res.string.section_sky, Res.string.purpose_sky, YacanaIcons.Sky, phase = 2),
    Sequence(Res.string.section_sequence, Res.string.purpose_sequence, YacanaIcons.Sequence, phase = 2),
    Imaging(Res.string.section_imaging, Res.string.purpose_imaging, YacanaIcons.Imaging, phase = 1),
}
