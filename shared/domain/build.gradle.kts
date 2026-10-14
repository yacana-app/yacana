// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kover)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "app.yacana.domain"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }
    // The JVM target runs the common tests on the PC.
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:ports"))
            // The domain drives the equipment through the Alpaca client (ARCHITECTURE.md).
            api(project(":shared:alpaca"))
            api(libs.kotlinx.coroutines.core)
            // Paths of the profile store are part of the API.
            api(libs.kotlinx.io.core)
            // Local dates for file names: a night folder takes the evening's date.
            api(libs.kotlinx.datetime)
            // Ephemerides of the Sun and the Moon.
            implementation(project(":shared:astronomy"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
    }
}

// Integration tests run only when OMNISIM_URL points to a running OmniSim.
tasks.named<Test>("jvmTest") {
    val omnisimUrl = providers.environmentVariable("OMNISIM_URL").orElse("")
    // Gradle does not track a test's environment, so a run with OmniSim must not reuse one without it.
    inputs.property("omnisimUrl", omnisimUrl)
    environment("OMNISIM_URL", omnisimUrl.get())
}
