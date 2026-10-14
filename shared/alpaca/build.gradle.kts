// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kover)
}

kotlin {
    android {
        namespace = "app.yacana.alpaca"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest {}
    }
    // The JVM target runs the common tests, and the OmniSim integration tests, on the PC.
    jvm()

    // Code on Java APIs that Android and the JVM share, such as UDP discovery on java.net sockets.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmCommon") {
                withJvm()
                withCompilations { it.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            // Device values are JsonElement in the public API.
            api(libs.kotlinx.serialization.json)
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
