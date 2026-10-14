// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import app.yacana.ui.AppPermission
import app.yacana.ui.PermissionStatus
import app.yacana.ui.PermissionsController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The permissions the first steps explain, on Android. Create it while the activity is created, since it
 * registers for the system's permission dialog, and call [refresh] when the activity resumes:
 * the user may come back from the settings screen.
 */
class AndroidPermissions(private val activity: ComponentActivity) : PermissionsController {
    private val prefs by lazy { activity.getSharedPreferences("permissions", Context.MODE_PRIVATE) }
    private val mutableStatuses = MutableStateFlow(emptyList<PermissionStatus>())
    private val dialog = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override val statuses: StateFlow<List<PermissionStatus>> = mutableStatuses.asStateFlow()

    fun refresh() {
        mutableStatuses.value = AppPermission.entries.map(::status)
    }

    @SuppressLint("BatteryLife") // Yacana is not on Google Play; the night session needs it.
    override fun request(permission: AppPermission) {
        val status = status(permission)
        when {
            permission == AppPermission.Battery -> open(
                if (status.granted) {
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                } else {
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri())
                },
            )

            permission == AppPermission.KeepWhenUnused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                open(Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, packageUri()))

            permission == AppPermission.Autostart -> {
                prefs.edit { putBoolean(permission.name, true) }
                open(Intent().setComponent(XIAOMI_AUTOSTART))
            }

            status.blocked -> open(appSettings())

            else -> {
                prefs.edit { putBoolean(permission.name, true) }
                dialog.launch(manifestPermissions(permission).toTypedArray())
            }
        }
    }

    private fun status(permission: AppPermission): PermissionStatus {
        val asked = prefs.getBoolean(permission.name, false)
        val needed =
            when (permission) {
                AppPermission.Battery -> true
                AppPermission.KeepWhenUnused -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                AppPermission.Autostart -> isXiaomi()
                else -> manifestPermissions(permission).isNotEmpty()
            }
        val granted =
            when (permission) {
                AppPermission.Battery -> activity.getSystemService(
                    PowerManager::class.java,
                ).isIgnoringBatteryOptimizations(activity.packageName)

                AppPermission.KeepWhenUnused ->
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && activity.packageManager.isAutoRevokeWhitelisted

                // Android cannot read Xiaomi's switch: opening its screen is as far as the app can tell.
                AppPermission.Autostart -> asked

                else -> manifestPermissions(permission).any { granted(it) }
            }
        // After a refusal the system may stop asking: then only the app's settings screen can grant it.
        val blocked =
            !granted && asked && manifestPermissions(permission).isNotEmpty() &&
                manifestPermissions(permission).none { activity.shouldShowRequestPermissionRationale(it) }
        return PermissionStatus(permission, granted = granted || !needed, needed = needed, blocked = blocked)
    }

    /** The Android permissions behind each one, or none where this version does not have it or it is a setting. */
    private fun manifestPermissions(permission: AppPermission): List<String> = when (permission) {
        AppPermission.LocalNetwork ->
            if (Build.VERSION.SDK_INT >= ANDROID_17) listOf(Manifest.permission.ACCESS_LOCAL_NETWORK) else emptyList()

        AppPermission.Location -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        AppPermission.Notifications ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

        AppPermission.Battery, AppPermission.KeepWhenUnused, AppPermission.Autostart -> emptyList()
    }

    /** Opens a settings screen, or the app's own settings where this phone does not have it. */
    private fun open(intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(appSettings())
        } catch (_: SecurityException) {
            activity.startActivity(appSettings())
        }
    }

    private fun appSettings() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())

    private fun packageUri() = "package:${activity.packageName}".toUri()

    /** Xiaomi, Redmi and Poco phones, which all have Xiaomi's Autostart switch. */
    private fun isXiaomi() = listOf(Build.MANUFACTURER, Build.BRAND).any { it.lowercase() in XIAOMI_BRANDS }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val ANDROID_17 = 37
        val XIAOMI_BRANDS = setOf("xiaomi", "redmi", "poco")
        val XIAOMI_AUTOSTART = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
    }
}
