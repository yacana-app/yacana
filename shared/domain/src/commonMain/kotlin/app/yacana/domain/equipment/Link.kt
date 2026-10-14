// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.equipment

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * How long the mount or the camera may go without a good read before the link counts as lost: a
 * Seestar can take many seconds to answer while it slews, as for each point of the polar
 * alignment, or hands over a frame; on the first night it always came back within 20 s.
 */
val LINK_STALE: Duration = 30.seconds

/**
 * Whether the link with the equipment is lost: the mount's or the camera's last good read
 * is older than [stale], or there was never one and the read failed. Read every second, a read
 * that fails now and then while the WiFi is there is not a lost link.
 */
fun ConnectedEquipment.linkLost(now: Instant, stale: Duration = LINK_STALE): Boolean =
    listOfNotNull(mount?.state?.value, camera?.state?.value).any { live ->
        val last = live.updatedAt
        if (last == null) live.error != null else now - last > stale
    }

/** How long without a good read, or with a failing one, counts as a hiccup of the link. */
val LINK_HICCUP: Duration = 5.seconds

/**
 * Whether the link is not answering well now, short of lost: the mount's or the camera's last read
 * failed, or their last good one is older than [hiccup]. A step that fails then failed for the
 * link, not for the sequence.
 */
fun ConnectedEquipment.linkTrouble(now: Instant, hiccup: Duration = LINK_HICCUP): Boolean =
    listOfNotNull(mount?.state?.value, camera?.state?.value).any { live ->
        live.error != null || live.updatedAt?.let { now - it > hiccup } == true
    }
