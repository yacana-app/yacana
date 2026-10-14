// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.domain.solver

import app.yacana.alpaca.AlpacaDiscovery
import app.yacana.domain.equipment.EquipmentController
import app.yacana.domain.equipment.EquipmentPlatform
import app.yacana.domain.profile.Profile
import app.yacana.domain.profile.ProfileOptions
import app.yacana.domain.profile.ProfilePresets
import app.yacana.domain.profile.ProfileStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StarDatabasesTest {
    private val directory = Path(SystemTemporaryDirectory, "yacana-databases-${ProfilePresets.newId()}")
    private val store = FakeStore()
    private val d50 = STAR_DATABASES.first { it.name == "D50" }
    private val d80 = STAR_DATABASES.first { it.name == "D80" }

    @AfterTest
    fun cleanUp() {
        if (SystemFileSystem.exists(directory)) {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    private fun TestScope.databases(): Pair<EquipmentController, StarDatabases> {
        val profiles = ProfileStore(directory)
        profiles.save(Profile("seestar", "Seestar", options = ProfileOptions(starDatabase = "D50")))
        profiles.activeId = "seestar"
        val platform =
            object : EquipmentPlatform {
                override fun httpClient(): HttpClient? = null

                override val discovery = AlpacaDiscovery { emptyList() }

                override fun networkHandle(): Long? = null

                override suspend fun location() = null
            }
        val equipment = EquipmentController(profiles, platform, backgroundScope)
        return equipment to StarDatabases(equipment, store, backgroundScope)
    }

    private fun EquipmentController.database() = state.value.active?.options?.starDatabase

    @Test
    fun theProfileKeepsItsDatabaseUntilTheNewOneIsOnThePhone() = runTest {
        val (equipment, databases) = databases()

        databases.download(d80)
        runCurrent()
        assertEquals("seestar", store.state.value.arriving.getValue("D80").owner)
        assertEquals("D50", equipment.database())

        store.finish("D80")
        runCurrent()
        assertEquals("D80", equipment.database())
    }

    @Test
    fun theDatabaseOfTheProfileInUseIsNotDeleted() = runTest {
        val (_, databases) = databases()
        store.finish("D80")

        databases.delete("D50")
        databases.delete("D80")

        assertEquals(setOf("D50"), store.state.value.installed)
    }

    @Test
    fun aCancelledOrFailedDownloadChangesNothing() = runTest {
        val (equipment, databases) = databases()

        databases.download(d80)
        runCurrent()
        databases.cancel("D80")
        runCurrent()
        store.state.update { it.copy(installed = it.installed + "D80") }
        runCurrent()

        assertEquals("D50", equipment.database())
    }

    @Test
    fun withoutRoomForTheDownloadAndItsFilesItDoesNotStart() = runTest {
        val (_, databases) = databases()
        store.state.update { it.copy(freeBytes = d80.downloadBytes + d80.installedBytes - 1) }

        databases.download(d80)
        runCurrent()

        assertEquals(
            DatabaseProblem.NoSpace("D80", d80.downloadBytes + d80.installedBytes, store.state.value.freeBytes),
            databases.state.value.problem,
        )
        assertEquals(emptyMap(), store.state.value.arriving)
    }

    @Test
    fun anImportedDatabaseIsUsedAsADownloadedOne() = runTest {
        val (equipment, databases) = databases()

        databases.import("content://g05_star_database.zip")
        runCurrent()

        assertEquals("G05", equipment.database())
        assertNull(databases.state.value.problem)
    }

    @Test
    fun aFileWithoutADatabaseIsSaid() = runTest {
        val (equipment, databases) = databases()

        databases.import("content://notes.zip")
        runCurrent()

        assertEquals(DatabaseProblem.NotADatabase, databases.state.value.problem)
        assertEquals("D50", equipment.database())
    }

    @Test
    fun aDatabaseNotOnThePhoneIsNeverUsed() = runTest {
        val (equipment, databases) = databases()

        databases.use("D80")
        runCurrent()

        assertEquals("D50", equipment.database())
    }

    @Test
    fun theRecommendationFollowsTheField() {
        assertEquals("D50", recommendedDatabase(null).name)
        assertEquals("D80", recommendedDatabase(0.15).name)
        // The Seestar S50 Pro's field.
        assertEquals("D50", recommendedDatabase(2.4).name)
        assertEquals("G05", recommendedDatabase(10.0).name)
        assertEquals("W08", recommendedDatabase(40.0).name)
    }

    private class FakeStore : StarDatabaseStore {
        override val state = MutableStateFlow(StarDatabaseFiles(installed = setOf("D50"), freeBytes = 20_000_000_000))

        override fun download(database: StarDatabase, owner: String) {
            state.update { it.copy(arriving = it.arriving + (database.name to Arrival(owner, ArrivalStep.Downloading))) }
        }

        override fun cancel(name: String) {
            state.update { it.copy(arriving = it.arriving - name) }
        }

        override suspend fun import(location: String): String {
            if ("star_database" !in location) throw StarDatabaseException(notADatabase = true, "No star database in $location")
            state.update { it.copy(installed = it.installed + "G05") }
            return "G05"
        }

        override fun delete(name: String) {
            state.update { it.copy(installed = it.installed - name) }
        }

        fun finish(name: String) {
            state.update { it.copy(installed = it.installed + name, arriving = it.arriving - name) }
        }
    }
}
