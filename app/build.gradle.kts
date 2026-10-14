// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.kover)
}

/** Release signing key. Read from keystore.properties, which git ignores; CI writes it from secrets. */
val keystore =
    rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }
    }

/** The last version tag and what came after it, as `git describe` says; "dev" without git or tags. */
fun gitVersion(): String =
    runCatching {
        providers
            .exec {
                commandLine("git", "describe", "--tags", "--match", "v*")
                isIgnoreExitValue = true
            }.standardOutput.asText
            .get()
            .trim()
            .removePrefix("v")
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "dev"

android {
    // The app changes its language itself: a bundle split by language would lack the others.
    bundle {
        language {
            enableSplit = false
        }
    }
    namespace = "app.yacana"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    // Same NDK as the engine, so that AGP can strip the native libraries.
    ndkVersion = libs.versions.android.ndk.get()

    defaultConfig {
        applicationId = "app.yacana"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // The release workflow passes both from the tag. Local builds take the version from
        // git, such as 0.1.2-18-g25efc8b, 18 commits after v0.1.2, so About never shows an old one.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: gitVersion()
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures { compose = true }

    // Screenshot tests run on the PC with Robolectric.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Robolectric reaches into the JDK's file descriptors on API 36.
            all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
        }
    }

    // ASTAP runs as a program, so Android must extract it with the native libraries (solvers/astap/).
    sourceSets.getByName("main").jniLibs.directories.add(rootProject.file("solvers/astap/android").path)
    packaging { jniLibs { useLegacyPackaging = true } }

    lint {
        warningsAsErrors = true
        // Versions are pinned on purpose in gradle/libs.versions.toml and updated deliberately.
        disable += listOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }
}

dependencies {
    implementation(project(":shared:ui"))
    implementation(project(":shared:alpaca"))
    implementation(project(":engine"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.xz)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.ktor.client.mock)
}

// The Maven groups the APK carries, for OpenSourceTest: "Settings · open-source licences" must name them all.
val runtimeGroupsFile = layout.buildDirectory.file("licences/runtime-groups.txt")
val runtimeGroups =
    tasks.register("runtimeGroups") {
        val root = configurations.named("releaseRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
        val out = runtimeGroupsFile
        outputs.file(out)
        outputs.upToDateWhen { false }
        doLast {
            val seen = mutableSetOf<org.gradle.api.artifacts.result.ResolvedComponentResult>()
            val next = ArrayDeque(listOf(root.get()))
            while (next.isNotEmpty()) {
                val component = next.removeFirst()
                if (seen.add(component)) {
                    component.dependencies
                        .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                        .forEach { next += it.selected }
                }
            }
            val groups = seen.mapNotNull { (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.group }.toSortedSet()
            out.get().asFile.writeText(groups.joinToString("\n"))
        }
    }
tasks.withType<Test>().configureEach {
    dependsOn(runtimeGroups)
    systemProperty("yacana.runtimeGroups", runtimeGroupsFile.get().asFile.path)
}

// Reference screenshots live in the repository, next to the tests that take them.
roborazzi { outputDir.set(file("src/test/screenshots")) }
