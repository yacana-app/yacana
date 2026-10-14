// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.session

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import app.yacana.domain.session.PhoneBattery

/**
 * The phone's battery for the session's guard, from Android's sticky battery
 * broadcast, read when asked. While the phone charges it only fills, so it says nothing then.
 */
class AndroidPhoneBattery(private val context: Context) : PhoneBattery {
    override fun percent(): Int? {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (plugged || level < 0 || scale <= 0) null else level * PERCENT / scale
    }

    private companion object {
        const val PERCENT = 100
    }
}
