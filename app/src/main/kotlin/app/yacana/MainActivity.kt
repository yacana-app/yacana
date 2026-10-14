// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.yacana.session.LogArchive
import app.yacana.ui.AppInfo
import app.yacana.ui.AppServices
import app.yacana.ui.FrameFolder
import app.yacana.ui.LogExport
import app.yacana.ui.YacanaApp
import app.yacana.ui.theme.YacanaThemeMode
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val engine get() = application.engine
    private val application get() = getApplication() as YacanaApplication
    private val display get() = application.display
    private val permissionsController = AndroidPermissions(this)
    private val files = AndroidFilePicker(this)
    private val languages by lazy { AppLanguages(this) }

    /** In the language chosen, where the system does not apply it itself (before Android 13). */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguages.applied(newBase))
    }

    @Suppress("LongMethod") // The app's services, one per line, and the screen's setup.
    override fun onCreate(savedInstanceState: Bundle?) {
        // Both themes are dark, so the system bars always use light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // While Yacana is in front the screen stays on and the phone does not lock: with the screen on,
        // Android keeps the WiFi out of its power save and joins the equipment's WiFi again at once if it
        // drops. The power button still turns it off, and the session goes on.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = AppInfo(versionName = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(), engine = engine.buildInfo())
        val frameFolder =
            object : FrameFolder {
                override val location = application.frameStore.location

                override fun change() = files.pickFolder(application.frameStore::choose)
            }
        val logExport =
            LogExport {
                files.saveZip("yacana-log-${LocalDate.now()}.zip") { out ->
                    LogArchive.write(
                        out,
                        application.journal.files(),
                        application.equipment.state.value.active,
                        application.versionName,
                        app.engine,
                    )
                }
            }
        val services =
            AppServices(
                display,
                permissionsController,
                files,
                application.firstSteps,
                application.equipment,
                application.capture,
                application.plateSolving,
                application.starDatabases,
                application.centring,
                application.polarAlignment,
                application.autofocus,
                application.sequence,
                application.guard,
                application.flats,
                application.objects,
                application.favourites,
                application.targets,
                application.savedSequences,
                application.blockTemplates,
                application.skyCaches,
                frameFolder,
                logExport,
                application.journal,
                languages = languages,
                beeper = ToneBeeper(),
                skyBrowsing = application.skyBrowsing,
            )
        setContent { YacanaApp(app, services) }
        applyRedMode()
    }

    /**
     * In red mode the system bars hide and the app sets its own brightness. While the
     * phone is the flats' light, its screen is at full brightness whatever the mode.
     */
    private fun applyRedMode() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(display.themeMode, display.redBrightness, application.flats.state) { mode, brightness, flats ->
                    Triple(mode, brightness, flats?.screenLit == true)
                }.collect { (mode, brightness, lit) ->
                    val red = mode == YacanaThemeMode.Red
                    val bars = WindowCompat.getInsetsController(window, window.decorView)
                    if (red) {
                        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        bars.hide(WindowInsetsCompat.Type.systemBars())
                    } else {
                        bars.show(WindowInsetsCompat.Type.systemBars())
                    }
                    window.attributes =
                        window.attributes.apply {
                            screenBrightness =
                                when {
                                    lit -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                                    red -> brightness
                                    else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                                }
                        }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may come back from the permission or battery settings.
        permissionsController.refresh()
    }
}
