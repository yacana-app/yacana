// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.session

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/** Battery, power and process facts that the reliability test records. */
class DeviceProbes(private val context: Context) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    /** Battery level in percent, or null if the phone does not report it. */
    fun batteryPercent(): Int? = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..FULL_PERCENT }

    /** Remaining charge in microampere-hours, or null if the phone does not report it. */
    fun chargeMicroAh(): Long? = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toLong().takeIf { it > 0 }

    fun charging(): Boolean = battery.isCharging

    fun screenOn(): Boolean = power.isInteractive

    fun deviceIdle(): Boolean = power.isDeviceIdleMode

    fun ignoringBatteryOptimizations(): Boolean = power.isIgnoringBatteryOptimizations(context.packageName)

    /**
     * Why the app's process ended before, newest first, after [sinceMillis]:
     * crashes, the low-memory killer or the system stopping the app (Android 11+).
     */
    fun processExitsSince(sinceMillis: Long): List<ProcessExit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val activities = context.getSystemService(ActivityManager::class.java)
        return activities
            .getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
            .filter { it.timestamp > sinceMillis }
            .map { ProcessExit(it.timestamp, it.reason, it.description.orEmpty(), it.importance) }
    }

    data class ProcessExit(val timestampMillis: Long, val reason: Int, val description: String, val importance: Int)

    private companion object {
        const val MAX_EXITS = 10
        const val FULL_PERCENT = 100
    }
}
