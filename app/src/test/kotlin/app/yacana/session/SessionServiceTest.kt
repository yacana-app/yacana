// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.session

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import app.yacana.YacanaApplication
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowPowerManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The night session: the foreground service that keeps the phone awake while
 * the equipment is connected, ends with it, stops from its notification, and comes back after the
 * system or a restart of the phone closed the app.
 */
@RunWith(RobolectricTestRunner::class)
class SessionServiceTest {
    private val app = ApplicationProvider.getApplicationContext<YacanaApplication>()
    private val prefs = app.getSharedPreferences("session", Context.MODE_PRIVATE)
    private var controller: ServiceController<SessionService>? = null

    @After
    fun tearDown() {
        controller?.destroy()
    }

    @Test
    fun theSessionRunsInTheForegroundWithThePhoneAwake() {
        val service = start(Intent(app, SessionService::class.java))

        val shadow = shadowOf(service)
        assertEquals(2, shadow.lastForegroundNotificationId)
        assertEquals("Connecting again after a restart…", shadowOf(shadow.lastForegroundNotification).contentText)
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        // The WiFi out of power save: with the screen off up to Android 13, and with it on and Yacana in front.
        assertEquals(2, wifiLocks())
    }

    @Test
    fun theSessionEndsWhenTheEquipmentIsNotConnected() {
        val service = start(Intent(app, SessionService::class.java))

        // The notification follows the equipment every second; disconnected, the session ends.
        assertTrue(eventually { shadowOf(service).isStoppedBySelf })
        assertTrue(shadowOf(service).isForegroundStopped)
        assertFalse(SessionService.wasActive(app))

        controller?.destroy()
        controller = null
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertEquals(0, wifiLocks())
    }

    private fun wifiLocks() = shadowOf(app.getSystemService(WifiManager::class.java)).activeLockCount

    @Test
    fun theNotificationsButtonStopsTheSession() {
        val service = Robolectric.buildService(SessionService::class.java).create().get()

        val result = service.onStartCommand(Intent(app, SessionService::class.java).setAction("app.yacana.session.STOP_SESSION"), 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(logged("stopped_from_notification"))
    }

    @Test
    fun aRestartBySystemWithoutASessionStopsAtOnce() {
        val service = Robolectric.buildService(SessionService::class.java).create().get()

        val result = service.onStartCommand(null, 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun aRestartBySystemDuringASessionResumesIt() {
        prefs.edit().putBoolean("active", true).putBoolean("looping", true).commit()
        val service = Robolectric.buildService(SessionService::class.java).create().get()

        val result = service.onStartCommand(null, 0, 1)

        assertEquals(Service.START_STICKY, result)
        assertTrue(eventually { logged("session_restarted") })
        assertTrue(app.journal.read().last { "session_restarted" in it }.contains("\"resumeLoop\":true"))
        service.onDestroy()
    }

    @Test
    fun connectingStartsTheSessionAndARestartOfThePhoneRestoresIt() {
        assertTrue(SessionService.start(app))
        assertEquals(SessionService::class.java.name, shadowOf(app).nextStartedService.component?.className)

        assertTrue(SessionService.restore(app))
        assertEquals("app.yacana.session.RESTORE_SESSION", shadowOf(app).nextStartedService.action)
    }

    @Test
    fun afterThePhoneRestartsNothingStartsWithoutASession() {
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun onlyTheRestartOfThePhoneIsHeard() {
        prefs.edit().putBoolean("active", true).commit()

        BootReceiver().onReceive(app, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))

        assertNull(shadowOf(app).nextStartedService)
    }

    private fun start(intent: Intent): SessionService =
        Robolectric.buildService(SessionService::class.java, intent).also { controller = it }.create().startCommand(0, 1).get()

    private fun logged(event: String) = app.journal.read().any { "\"event\":\"$event\"" in it }

    /** Whether [condition] holds within a few seconds: the session follows the equipment off the main thread. */
    private fun eventually(condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + WAIT_MILLIS
        while (!condition()) {
            if (System.currentTimeMillis() > end) return false
            Thread.sleep(STEP_MILLIS)
        }
        return true
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
        const val STEP_MILLIS = 50L
    }
}
