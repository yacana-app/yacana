// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "yacana"

include(":app")
include(":shared:ports", ":shared:domain", ":shared:alpaca", ":shared:ui")
// Astronomy Engine's source, as its author writes it (shared/astronomy/README.md).
include(":shared:astronomy")

// The Gradle side of the Rust engine; the crate itself is engine/Cargo.toml.
include(":engine")
project(":engine").projectDir = file("engine/android")
