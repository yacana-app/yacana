// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.library) apply false
    // Declared here so that AGP's built-in Kotlin uses this Kotlin version too.
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlinter) apply false
    alias(libs.plugins.roborazzi) apply false
    // The coverage of every module in one report.
    alias(libs.plugins.kover)
}

// One report of the whole app's coverage, without the generated Compose resources' accessors.
kover {
    reports {
        filters { excludes { packages("app.yacana.ui.resources") } }
    }
}

dependencies {
    kover(project(":shared:alpaca"))
    kover(project(":shared:domain"))
    kover(project(":shared:ui"))
    kover(project(":app"))
}

/** OmniSim is one shared server: the integration tests of different modules must not drive it at the same time. */
abstract class OmniSim : BuildService<BuildServiceParameters.None>

val omniSim = gradle.sharedServices.registerIfAbsent("omniSim", OmniSim::class) { maxParallelUsages = 1 }

// Every Kotlin module gets the same linters: ktlint (through Kotlinter) and detekt.
subprojects {
    tasks.withType<Test>().matching { it.name == "jvmTest" }.configureEach { usesService(omniSim) }
    if (!buildFile.exists()) return@subprojects
    // Third-party code keeps its author's style.
    if (path == ":shared:astronomy") return@subprojects
    apply(plugin = "org.jmailen.kotlinter")
    apply(plugin = "dev.detekt")
    // Generated code (Compose resources, UniFFI bindings) lives in build/ and is not linted.
    tasks.withType<org.jmailen.gradle.kotlinter.tasks.LintTask>().configureEach { exclude { it.file.invariantSeparatorsPath.contains("/build/") } }
    tasks.withType<org.jmailen.gradle.kotlinter.tasks.FormatTask>().configureEach { exclude { it.file.invariantSeparatorsPath.contains("/build/") } }
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        source.setFrom(fileTree("src") { include("**/*.kt") })
    }
}
