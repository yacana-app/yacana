// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import android.Manifest
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import app.yacana.ui.AppPermission
import app.yacana.ui.PermissionStatus
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The permissions the first steps explain, as Android has them: what each one is, granted or not, and what asking for it opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidPermissionsTest {
    private val screen = Robolectric.buildActivity(ComponentActivity::class.java).create()
    private val activity = screen.get()

    // Made as the activity is created, as the app does, before it starts.
    private val permissions = AndroidPermissions(activity).also { screen.start().resume() }

    private fun status(permission: AppPermission): PermissionStatus {
        permissions.refresh()
        return permissions.statuses.value.single { it.permission == permission }
    }

    @Test
    fun theLocationIsGrantedOnceAndroidGrantsIt() {
        assertFalse(status(AppPermission.Location).granted)

        shadowOf(activity.application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertTrue(status(AppPermission.Location).granted)
    }

    @Test
    fun askingForTheLocationOpensAndroidsDialog() {
        permissions.request(AppPermission.Location)

        val asked = shadowOf(activity).lastRequestedPermission.requestedPermissions.toList()
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), asked)
    }

    @Test
    fun aPermissionRefusedForGoodOpensTheAppsSettings() {
        permissions.request(AppPermission.Notifications)
        // Refused, and Android will not ask again.
        assertTrue(status(AppPermission.Notifications).blocked)

        permissions.request(AppPermission.Notifications)

        val opened = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.action)
        assertEquals("package:${activity.packageName}", opened.dataString)
    }

    @Test
    fun theBatteryAsksToBeLeftAloneUntilItIs() {
        assertFalse(status(AppPermission.Battery).granted)
        permissions.request(AppPermission.Battery)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, shadowOf(activity).nextStartedActivity.action)

        shadowOf(activity.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(activity.packageName, true)
        assertTrue(status(AppPermission.Battery).granted)
        permissions.request(AppPermission.Battery)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, shadowOf(activity).nextStartedActivity.action)
    }

    @Test
    fun keepingTheAppWhenUnusedOpensItsScreen() {
        permissions.request(AppPermission.KeepWhenUnused)

        assertEquals(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, shadowOf(activity).nextStartedActivity.action)
    }

    @Test
    fun autostartIsOnlyXiaomis() {
        assertFalse(status(AppPermission.Autostart).needed)
        assertTrue(status(AppPermission.Autostart).granted)

        ShadowBuild.setManufacturer("Xiaomi")
        assertTrue(status(AppPermission.Autostart).needed)
        assertFalse(status(AppPermission.Autostart).granted)

        permissions.request(AppPermission.Autostart)
        assertEquals("com.miui.securitycenter", shadowOf(activity).nextStartedActivity.component?.packageName)
        // Android cannot read Xiaomi's switch: opening its screen is as far as the app can tell.
        assertTrue(status(AppPermission.Autostart).granted)
    }

    @Test
    fun theLocalNetworkIsNotAskedForBeforeAndroid17() {
        val local = status(AppPermission.LocalNetwork)

        assertFalse(local.needed)
        assertTrue(local.granted)
    }
}
