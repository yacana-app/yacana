// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package app.yacana.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import app.yacana.ui.design.DeviceRow
import app.yacana.ui.design.RowChevron
import app.yacana.ui.design.SectionCard
import app.yacana.ui.navigation.Screen
import app.yacana.ui.resources.Res
import app.yacana.ui.resources.oss_catalogue
import app.yacana.ui.resources.oss_code
import app.yacana.ui.resources.oss_data
import app.yacana.ui.resources.oss_intro
import app.yacana.ui.resources.oss_what_androidx
import app.yacana.ui.resources.oss_what_astap
import app.yacana.ui.resources.oss_what_astronomy
import app.yacana.ui.resources.oss_what_cds
import app.yacana.ui.resources.oss_what_compose
import app.yacana.ui.resources.oss_what_crates
import app.yacana.ui.resources.oss_what_dss
import app.yacana.ui.resources.oss_what_erfa
import app.yacana.ui.resources.oss_what_gaia
import app.yacana.ui.resources.oss_what_guava
import app.yacana.ui.resources.oss_what_hocus
import app.yacana.ui.resources.oss_what_jna
import app.yacana.ui.resources.oss_what_jpeg
import app.yacana.ui.resources.oss_what_jspecify
import app.yacana.ui.resources.oss_what_kotlin
import app.yacana.ui.resources.oss_what_ktor
import app.yacana.ui.resources.oss_what_nina
import app.yacana.ui.resources.oss_what_nsns
import app.yacana.ui.resources.oss_what_okhttp
import app.yacana.ui.resources.oss_what_openngc
import app.yacana.ui.resources.oss_what_rayon
import app.yacana.ui.resources.oss_what_rust
import app.yacana.ui.resources.oss_what_slf4j
import app.yacana.ui.resources.oss_what_socket2
import app.yacana.ui.resources.oss_what_tabler
import app.yacana.ui.resources.oss_what_tppa
import app.yacana.ui.resources.oss_what_uniffi
import app.yacana.ui.resources.oss_what_xz
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Something the app carries or uses, with its licence: [name], what it does in the app,
 * its [licence] as the list shows it, where its code or data is, the [notices] its licence asks
 * to keep, such as its copyright, and the [texts] of its licence, files of `files/licences/`.
 * [groups] are the Maven groups of the APK it covers, so a test can tell that none is missing;
 * one that ends in a dot covers every group under it.
 */
data class OpenSource(
    val id: String,
    val name: String,
    val what: StringResource,
    val licence: String,
    val url: String,
    val notices: List<String> = emptyList(),
    val texts: List<String> = listOf(licence),
    val data: Boolean = false,
    val groups: List<String> = emptyList(),
)

/** One of two licences, used under the Apache License, as most Rust crates allow. */
private const val UNDER_APACHE = "Used under the Apache License 2.0, one of its two licences; the other is MIT."
private const val DUAL = "MIT OR Apache-2.0"

/** Everything in "Settings · open-source licences", code and then data, each in alphabetical order. */
val OPEN_SOURCE: List<OpenSource> =
    listOf(
        OpenSource(
            "androidx",
            "AndroidX",
            Res.string.oss_what_androidx,
            "Apache-2.0",
            "https://developer.android.com/jetpack/androidx",
            listOf("Copyright © The Android Open Source Project"),
            groups = listOf("androidx."),
        ),
        OpenSource(
            "astap",
            "ASTAP",
            Res.string.oss_what_astap,
            "MPL-2.0",
            "https://sourceforge.net/projects/astap-program/",
            listOf("Copyright © Han Kleijn"),
        ),
        OpenSource(
            "astronomy",
            "Astronomy Engine",
            Res.string.oss_what_astronomy,
            "MIT",
            "https://github.com/cosinekitty/astronomy",
            listOf("Copyright (c) 2019-2023 Don Cross"),
        ),
        OpenSource(
            "compose",
            "Compose Multiplatform",
            Res.string.oss_what_compose,
            "Apache-2.0",
            "https://github.com/JetBrains/compose-multiplatform",
            listOf("Copyright © JetBrains s.r.o. and the Android Open Source Project"),
            groups = listOf("org.jetbrains.compose.", "org.jetbrains.androidx."),
        ),
        OpenSource(
            "erfa",
            "ERFA",
            Res.string.oss_what_erfa,
            "BSD-3-Clause",
            "https://github.com/liberfa/erfa",
            texts = listOf("ERFA"),
        ),
        OpenSource(
            "guava",
            "Guava",
            Res.string.oss_what_guava,
            "Apache-2.0",
            "https://github.com/google/guava",
            listOf("Copyright © The Guava Authors"),
            groups = listOf("com.google.guava"),
        ),
        OpenSource(
            "hocus",
            "Hocus Focus",
            Res.string.oss_what_hocus,
            "MPL-2.0",
            "https://github.com/ghilios/joko.ninaplugins",
            listOf("Copyright © 2021-2026 George Hilios"),
        ),
        OpenSource(
            "jna",
            "JNA",
            Res.string.oss_what_jna,
            "Apache-2.0",
            "https://github.com/java-native-access/jna",
            listOf("Used under the Apache License 2.0, one of its two licences; the other is LGPL 2.1."),
            groups = listOf("net.java.dev.jna"),
        ),
        OpenSource(
            "jpeg",
            "jpeg-encoder",
            Res.string.oss_what_jpeg,
            "$DUAL · IJG",
            "https://github.com/vstroebel/jpeg-encoder",
            listOf(
                "Copyright (c) 2021 Volker Ströbel",
                "This software is based in part on the work of the Independent JPEG Group.",
                UNDER_APACHE,
            ),
            texts = listOf("Apache-2.0"),
        ),
        OpenSource(
            "jspecify",
            "JSpecify",
            Res.string.oss_what_jspecify,
            "Apache-2.0",
            "https://jspecify.dev",
            listOf("Copyright © The JSpecify Authors"),
            groups = listOf("org.jspecify"),
        ),
        OpenSource(
            "kotlin",
            "Kotlin and kotlinx",
            Res.string.oss_what_kotlin,
            "Apache-2.0",
            "https://kotlinlang.org",
            listOf("Copyright © JetBrains s.r.o. and Kotlin Programming Language contributors"),
            groups = listOf("org.jetbrains", "org.jetbrains.kotlin", "org.jetbrains.kotlinx"),
        ),
        OpenSource(
            "ktor",
            "Ktor",
            Res.string.oss_what_ktor,
            "Apache-2.0",
            "https://ktor.io",
            listOf("Copyright © JetBrains s.r.o."),
            groups = listOf("io.ktor"),
        ),
        OpenSource(
            "nina",
            "N.I.N.A.",
            Res.string.oss_what_nina,
            "MPL-2.0",
            "https://github.com/isbeorn/nina",
            listOf("Copyright © 2016-2026 Stefan Berg and the N.I.N.A. contributors"),
        ),
        OpenSource(
            "okhttp",
            "OkHttp and Okio",
            Res.string.oss_what_okhttp,
            "Apache-2.0",
            "https://square.github.io/okhttp/",
            listOf("Copyright © Square, Inc."),
            groups = listOf("com.squareup.okhttp3", "com.squareup.okio"),
        ),
        OpenSource(
            "rayon",
            "rayon",
            Res.string.oss_what_rayon,
            DUAL,
            "https://github.com/rayon-rs/rayon",
            listOf(UNDER_APACHE),
            texts = listOf("Apache-2.0"),
        ),
        OpenSource(
            "rust",
            "Rust",
            Res.string.oss_what_rust,
            DUAL,
            "https://www.rust-lang.org",
            listOf(UNDER_APACHE),
            texts = listOf("Apache-2.0"),
        ),
        OpenSource(
            "crates",
            "Rust crates",
            Res.string.oss_what_crates,
            "MIT · Apache-2.0",
            "https://crates.io",
            listOf(
                "anyhow, bitflags, camino, cargo-platform, cfg-if, crossbeam, either, equivalent, errno, fastrand, getrandom, " +
                    "hashbrown, heck, indexmap, itoa, libc, linux-raw-sys, memchr, once_cell, rustix, semver, serde, serde_json, " +
                    "static_assertions, tempfile and thiserror: $DUAL, used under the Apache License 2.0.",
                "bytes: Copyright (c) 2018 Carl Lerche, MIT.",
                "cargo_metadata and zmij: MIT.",
            ),
            texts = listOf("MIT", "Apache-2.0"),
        ),
        OpenSource(
            "slf4j",
            "SLF4J",
            Res.string.oss_what_slf4j,
            "MIT",
            "https://www.slf4j.org",
            listOf("Copyright (c) 2004-2023 QOS.ch", "All rights reserved."),
            groups = listOf("org.slf4j"),
        ),
        OpenSource(
            "socket2",
            "socket2",
            Res.string.oss_what_socket2,
            DUAL,
            "https://github.com/rust-lang/socket2",
            listOf(UNDER_APACHE),
            texts = listOf("Apache-2.0"),
        ),
        OpenSource(
            "tabler",
            "Tabler Icons",
            Res.string.oss_what_tabler,
            "MIT",
            "https://github.com/tabler/tabler-icons",
            listOf("Copyright (c) 2020-2026 Paweł Kuna"),
        ),
        OpenSource(
            "tppa",
            "Three Point Polar Alignment",
            Res.string.oss_what_tppa,
            "MPL-2.0",
            "https://github.com/isbeorn/nina.plugin.polaralignment",
            listOf("Copyright © Stefan Berg and the N.I.N.A. contributors"),
        ),
        OpenSource(
            "uniffi",
            "UniFFI",
            Res.string.oss_what_uniffi,
            "MPL-2.0",
            "https://github.com/mozilla/uniffi-rs",
            listOf("Copyright © Mozilla Foundation"),
        ),
        OpenSource(
            "xz",
            "XZ for Java",
            Res.string.oss_what_xz,
            "0BSD",
            "https://tukaani.org/xz/java.html",
            listOf("Lasse Collin and the Tukaani project"),
            groups = listOf("org.tukaani"),
        ),
        // The data: none of it is in the APK but the catalogue made from OpenNGC.
        OpenSource("dss", "DSS", Res.string.oss_what_dss, "STScI", "https://archive.stsci.edu/dss/", texts = listOf("DSS"), data = true),
        OpenSource(
            "gaia",
            "Gaia (ESA)",
            Res.string.oss_what_gaia,
            "CC BY-NC 3.0 IGO",
            "https://www.cosmos.esa.int/gaia",
            texts = listOf("Gaia"),
            data = true,
        ),
        OpenSource(
            "nsns",
            "Northern Sky Narrowband Survey",
            Res.string.oss_what_nsns,
            "CC BY-NC-SA 4.0",
            "https://www.simg.de/nebulae3/",
            texts = listOf("NSNS"),
            data = true,
        ),
        OpenSource(
            "openngc",
            "OpenNGC",
            Res.string.oss_what_openngc,
            "CC BY-SA 4.0",
            "https://github.com/mattiaverga/OpenNGC",
            texts = listOf("OpenNGC"),
            data = true,
        ),
        OpenSource(
            "cds",
            "SIMBAD, Sesame and hips2fits",
            Res.string.oss_what_cds,
            "CDS",
            "https://cds.unistra.fr",
            texts = listOf("CDS"),
            data = true,
        ),
    )

/** "Settings · open-source licences": the code the app carries and the data it uses, each opening its licence. */
@Composable
fun LicencesScreen(open: (Screen) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Note(stringResource(Res.string.oss_intro))
        Group(stringResource(Res.string.oss_code), OPEN_SOURCE.filterNot { it.data }, open)
        Group(stringResource(Res.string.oss_data), OPEN_SOURCE.filter { it.data }, open)
        Note(stringResource(Res.string.oss_catalogue))
    }
}

@Composable
private fun Group(title: String, items: List<OpenSource>, open: (Screen) -> Unit) {
    SectionCard {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEachIndexed { index, item ->
            DeviceRow(item.name, null, last = index == items.lastIndex, onClick = { open(Screen.Licence(item.id)) }) {
                Text(item.licence, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RowChevron()
            }
        }
    }
}

/**
 * "Settings · open-source licence": what [id] is, its licence and where it is, and the whole text
 * of its licence after the notices it asks to keep.
 */
@Composable
fun LicenceScreen(id: String) {
    val item = OPEN_SOURCE.first { it.id == id }
    val text by produceState("", item) {
        val notices = item.notices.joinToString("\n")
        value = (listOf(notices) + item.texts.map { readLicence(it) }).filter { it.isNotBlank() }.joinToString("\n\n")
    }
    val uri = LocalUriHandler.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard {
            Text(item.name, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(item.what) + " · " + item.licence,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                item.url.removePrefix("https://").removePrefix("www.").trimEnd('/'),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp).clickable { uri.openUri(item.url) },
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

/** The text of the licence [name], from `files/licences/`. */
private suspend fun readLicence(name: String): String = Res.readBytes("files/licences/$name.txt").decodeToString().trim()
