// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.sequence

import app.yacana.domain.profile.Pace
import app.yacana.domain.profile.Profile
import app.yacana.domain.sequence.Trigger

/** How long the night's steps take with [this] profile's equipment, as its last night measured. */
internal fun Profile?.pace(): Pace = this?.options?.pace ?: Pace()

/** The [triggers] whose autofocus runs the plan can foresee: none without a focuser to run them. */
internal fun Profile?.foreseeing(triggers: List<Trigger>): List<Trigger> = if (this?.devices?.focuser != null) triggers else emptyList()
