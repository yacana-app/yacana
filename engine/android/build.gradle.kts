// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.yacana.engine"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = libs.versions.android.ndk.get()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(project(":shared:ports"))
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.kotlinx.coroutines.core)
}

/** The Android ABIs we ship: ARM64 for phones, x86_64 for the emulator. */
val engineAbis = listOf("arm64-v8a", "x86_64")
val engineCrateDir = rootProject.layout.projectDirectory.dir("engine")

/** `cargo` from PATH, or from rustup's default folder when the IDE started before Rust was installed. */
val cargoExe: String =
    providers
        .environmentVariable("CARGO")
        .orElse(
            providers.systemProperty("user.home").map { home ->
                val exe = if (System.getProperty("os.name").startsWith("Windows")) "cargo.exe" else "cargo"
                File(home, ".cargo/bin/$exe").takeIf { it.isFile }?.path ?: "cargo"
            },
        ).get()

/** Builds the Rust crate for every ABI with cargo-ndk, 16 KB aligned (see engine/.cargo/config.toml). */
abstract class CargoNdkBuild : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations

    @get:Input abstract val cargo: Property<String>

    @get:Input abstract val abis: ListProperty<String>

    @get:Input abstract val minSdk: Property<Int>

    @get:Internal abstract val crateDir: DirectoryProperty

    @get:Internal abstract val ndkDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        exec.exec {
            workingDir = crateDir.get().asFile
            environment("ANDROID_NDK_HOME", ndkDir.get().asFile.path)
            val targets = abis.get().flatMap { listOf("-t", it) }
            commandLine(
                listOf(cargo.get(), "ndk") + targets +
                    listOf("-P", minSdk.get().toString(), "-o", out.path, "build", "--release", "-p", "yacana-engine"),
            )
        }
    }
}

/** Generates the Kotlin bindings from the compiled library (UniFFI library mode). */
abstract class UniffiBindgen : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations

    @get:Input abstract val cargo: Property<String>

    @get:Internal abstract val crateDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val library: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val config: RegularFileProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        exec.exec {
            workingDir = crateDir.get().asFile
            commandLine(
                cargo.get(), "run", "--quiet", "-p", "uniffi-bindgen", "--",
                "generate", library.get().asFile.path,
                "--language", "kotlin", "--no-format", "--out-dir", out.path,
            )
        }
    }
}

val cargoNdkBuild =
    tasks.register<CargoNdkBuild>("cargoNdkBuild") {
        group = "build"
        description = "Builds the Rust engine for Android with cargo-ndk."
        cargo = cargoExe
        abis = engineAbis
        minSdk = libs.versions.android.minSdk.get().toInt()
        crateDir = engineCrateDir
        ndkDir = androidComponents.sdkComponents.ndkDirectory
        sources.from(
            engineCrateDir.dir("src"),
            engineCrateDir.file("Cargo.toml"),
            engineCrateDir.file("Cargo.lock"),
            engineCrateDir.file(".cargo/config.toml"),
        )
        outputDir = layout.buildDirectory.dir("rust/jniLibs")
    }

val uniffiBindgen =
    tasks.register<UniffiBindgen>("uniffiBindgen") {
        group = "build"
        description = "Generates the Kotlin bindings of the Rust engine."
        cargo = cargoExe
        crateDir = engineCrateDir
        library = cargoNdkBuild.flatMap { it.outputDir.file("arm64-v8a/libyacana_engine.so") }
        config = engineCrateDir.file("uniffi.toml")
        outputDir = layout.buildDirectory.dir("generated/uniffi")
    }

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdkBuild, CargoNdkBuild::outputDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(uniffiBindgen, UniffiBindgen::outputDir)
    }
}
