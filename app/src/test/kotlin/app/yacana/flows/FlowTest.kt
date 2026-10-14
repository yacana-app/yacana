// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.flows

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import app.yacana.FakeSeestar
import app.yacana.FakeSkyCaches
import app.yacana.TEST_APP_INFO
import app.yacana.TEST_WAIT_MILLIS
import app.yacana.TestApp
import app.yacana.domain.sky.NameResolver
import app.yacana.ui.AppServices
import app.yacana.ui.Section
import app.yacana.ui.YacanaApp
import app.yacana.ui.navigation.Navigator
import app.yacana.ui.navigation.Screen
import app.yacana.ui.settings.LanguageSettings
import app.yacana.ui.settings.SystemLanguage
import app.yacana.ui.theme.YacanaThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * What the app does when it is touched: the whole app, as the screenshots draw it, with
 * the fake Seestar's profile and tonight's targets, opened at a screen and then tapped as a user
 * would. The texts are the English ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-mdpi")
abstract class FlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val directory: File = Files.createTempDirectory("yacana-flow").toFile()

    /** The theme the app shows in. */
    protected open val mode = YacanaThemeMode.Dark

    private val app by lazy { TestApp(scope, directory, mode) }

    /** The fake Seestar the app talks to, and what it was sent. */
    protected val seestar: FakeSeestar get() = app.seestar

    /** Whether the Seestar was sent [member], with [parameter] set to [value] when given, waiting a few seconds for it. */
    protected fun sent(member: String, parameter: String? = null, value: String? = null) = eventually {
        synchronized(seestar.puts) {
            seestar.puts.any { (sentMember, form) -> sentMember == member && (parameter == null || form[parameter] == value) }
        }
    }

    /** How many times the session log was exported. */
    protected val logExports get() = app.logExports

    protected val beeps get() = app.beeps

    /** What the star databases' store was asked, in order. */
    protected fun databaseRequests() = synchronized(app.databaseRequests) { app.databaseRequests.toList() }

    @After
    fun tearDown() {
        scope.cancel()
        directory.deleteRecursively()
    }

    /**
     * The app at [screens] of [section], with the fake Seestar's profile and, when [framed],
     * tonight's targets; with [advanced], tonight as the advanced editor leaves it; and the equipment
     * [connected] or not; and SIMBAD as [resolver] answers.
     */
    protected fun show(
        section: Section,
        vararg screens: Screen,
        framed: Boolean = true,
        advanced: Boolean = false,
        connected: Boolean = false,
        resolver: NameResolver? = null,
        languages: LanguageSettings = SystemLanguage(),
    ): AppServices {
        val equipment = app.equipment()
        runBlocking { equipment.use(equipment.discover().single()) }
        if (connected) app.connect(equipment)
        val services =
            app.services(
                equipment,
                firstStepsDone = true,
                lastAutofocus = null,
                skyCaches = FakeSkyCaches(null),
                framed = framed,
                advanced = advanced,
                resolver = resolver,
                languages = languages,
            )
        // The clock moves only as a test says: a screen's transition would never leave the test rule idle on its own.
        compose.mainClock.autoAdvance = false
        // Made once: a navigator made as the content composes would start again at each recomposition.
        val navigator = Navigator(section).apply { screens.forEach(::open) }
        compose.setContent { YacanaApp(TEST_APP_INFO, services, navigator = navigator) }
        settle()
        return services
    }

    /** Lets three seconds go by, enough for a screen's transition or a sheet's to end. */
    protected fun settle() {
        // In steps: a sheet opens a step after its screen recomposes, as its effect hands it to the app's sheet host.
        repeat(SETTLE_STEPS) {
            compose.mainClock.advanceTimeBy(SETTLE_MILLIS / SETTLE_STEPS)
            compose.waitForIdle()
        }
    }

    /**
     * Whether [condition] holds within [TEST_WAIT_MILLIS], moving the screen's clock and waiting on
     * the real one: what the app does off the screen, as talking to the Seestar, runs on the real clock.
     */
    protected fun eventually(condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + TEST_WAIT_MILLIS
        while (!condition()) {
            if (System.currentTimeMillis() > end) return false
            compose.mainClock.advanceTimeBy(SETTLE_MILLIS / SETTLE_STEPS)
            compose.waitForIdle()
            Thread.sleep(REAL_STEP_MILLIS)
        }
        return true
    }

    /** The node showing [text], waiting a few seconds for it; the last one when a sheet over the screen shows it too. */
    protected fun node(text: String, substring: Boolean = false): SemanticsNodeInteraction {
        eventually { shows(text, substring) }
        return compose.onAllNodesWithText(text, substring).onLast()
    }

    /**
     * Taps what shows [text] and can be tapped; the last one when a sheet shows it too. It is not
     * scrolled to: a tap needs no scroll, and scrolling to a row of a long list can loop for ever.
     */
    protected fun tap(text: String, substring: Boolean = false) {
        node(text, substring)
        compose.onAllNodes(hasText(text, substring) and hasClickAction()).onLast().performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    /** Taps the button called [description], as an icon's. */
    protected fun tapIcon(description: String) {
        compose.onNodeWithContentDescription(description).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    /** Taps what says [label] it does when tapped, as the shutter's "Capture". */
    protected fun tapLabelled(label: String) {
        compose.onNode(labelled(label)).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    /** Whether something says [label] it does when tapped, within a few seconds. */
    protected fun showsLabelled(label: String) = eventually { compose.onAllNodes(labelled(label)).fetchSemanticsNodes().isNotEmpty() }

    private fun labelled(label: String) = SemanticsMatcher("taps to $label") {
        SemanticsActions.OnClick in it.config && it.config[SemanticsActions.OnClick].label == label
    }

    /** Turns the [index]th switch of the screen. */
    protected fun toggle(index: Int = 0) {
        compose.onAllNodes(isToggleable())[index].performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    /** Types [text] in the last text field, a sheet's over the screen's, as the phone's keyboard would. */
    protected fun type(text: String) {
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput(text)
        settle()
    }

    /** Holds the button that shows [text], one that acts after it is held. */
    protected fun hold(text: String) {
        compose.onNode(hasAnyDescendant(hasText(text)) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .performSemanticsAction(SemanticsActions.OnLongClick)
        settle()
    }

    /** Whether something shows [text] within a few seconds. */
    protected fun appears(text: String, substring: Boolean = false): Boolean {
        node(text, substring)
        return shows(text, substring)
    }

    /** Whether something shows [text] now. */
    protected fun shows(text: String, substring: Boolean = false) =
        compose.onAllNodesWithText(text, substring).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val SETTLE_MILLIS = 3_000L
        const val SETTLE_STEPS = 6

        /** How long [eventually] waits on the real clock between looks. */
        const val REAL_STEP_MILLIS = 50L
    }
}
