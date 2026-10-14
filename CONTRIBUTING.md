# Contributing to Yacana

Thanks for helping. Yacana is a personal and community project: we keep it simple and add only what is needed to use it on a real night.

## Before you start

- Read [`ARCHITECTURE.md`](ARCHITECTURE.md) first: how the app is built and why (about 10 minutes).
- For the look of the app, read the [style guide](docs/design/STYLE.md).
- We reopen a decision only with new data: a measurement, an external change or a new need.

## Set up

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 21 (Temurin) | Gradle and Android Studio use it |
| Android Studio | Latest stable | Installs the Android SDK. Install the NDK version pinned in `gradle/libs.versions.toml` from its SDK Manager |
| Rust | Stable, through `rustup` | Plus the Android targets and `cargo-ndk`, see below |
| Python | 3.10 or later | Scripts in `tools/` |
| ASCOM Alpaca Simulators (OmniSim) | Latest release | Integration tests; it listens on port 32323 |
| Visual Studio Build Tools (Windows only) | 2022, C++ workload | Rust needs the MSVC linker for host builds |

```bash
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
```

Versions of Gradle plugins, libraries, the SDK and the NDK live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Build and test

```bash
./gradlew assembleDebug   # debug APK in app/build/outputs/apk/debug/
./gradlew check           # unit tests, ktlint and detekt
cargo test --manifest-path engine/Cargo.toml
cargo clippy --manifest-path engine/Cargo.toml -- -D warnings
```

UI changes need new reference screenshots. They are taken with Robolectric and Roborazzi in both themes and both orientations, and in red mode the test fails if any pixel has green or blue:

```bash
./gradlew :app:recordRoborazziDebug   # records them into app/src/test/screenshots
./gradlew :app:verifyRoborazziDebug   # what CI runs
```

If CI sees differences that only come from rendering on Linux, run the CI workflow by hand with "Record the reference screenshots" ticked, and commit the screenshots it uploads.

Integration tests talk to OmniSim and are skipped unless `OMNISIM_URL` is set. Start OmniSim, then:

```bash
OMNISIM_URL=http://localhost:32323 ./gradlew :shared:alpaca:jvmTest
```

These tests expect OmniSim's default settings.

### Same numbers as N.I.N.A.

The engine finds stars and measures their HFR as N.I.N.A. does, its statistics are NINA's, and it fits each star's FWHM as the Hocus Focus plugin does. To check that on real frames, a Windows PC with NINA 3 installed prints NINA's own numbers for them with `tools/nina-reference/run.sh`, and the plugin's FWHM too when Hocus Focus is installed (it builds a small tool against them with the C# compiler of Visual Studio or its Build Tools). Then the engine's test compares, frame by frame:

```bash
tools/nina-reference/run.sh frames/*.fits > nina.json
YACANA_FITS_DIR=frames YACANA_NINA_JSON=nina.json cargo test --manifest-path engine/Cargo.toml --release it_matches_nina -- --ignored --nocapture
```

Frames hold the site's coordinates in their headers, so they stay out of git (see Test data and secrets).

The polar alignment computes the error as the Three Point Polar Alignment plugin does. With the plugin installed in NINA, `tools/nina-reference/tppa.sh` runs the plugin's own code on cases of three plate solves, one per line, and prints its error; the cases and its answers are in `PolarErrorAsThePluginTest`, which checks Yacana within 2″ per axis. To add cases, run them through the tool and copy its answers:

```bash
tools/nina-reference/tppa.sh < cases.txt
```

The photos the map fetches online are kept as N.I.N.A. keeps its cache. To check that NINA reads them, export *Online photos* from *Settings · sky map* as a ZIP, unzip it on the PC, and run `tools/nina-reference/cache.sh` on the folder: NINA's own code loads each photo, prints it, and draws its offline sky map around the first one:

```bash
tools/nina-reference/cache.sh FramingAssistantCache_Online map.png
```

The ephemerides are checked against JPL Horizons. `tools/horizons/reference.py` asks Horizons for the Sun's and the Moon's altitude minute by minute on a few nights at public observatories and prints the rise, set and twilight times, and their places, as `HorizonsReference.kt`; `EphemeridesTest` checks Yacana within 1 min and 1′. It needs the internet:

```bash
python tools/horizons/reference.py > shared/domain/src/commonTest/kotlin/app/yacana/domain/sky/HorizonsReference.kt
```

The catalogue of objects is `shared/ui/src/commonMain/composeResources/files/catalogue.tsv`, made by `catalog/build.py` from a release of OpenNGC and the common names in English and Spanish of `catalog/names.tsv`. To bring a new release or a new name, change the script's `RELEASE` or the names, and run it; it downloads OpenNGC, or takes a folder with its `NGC.csv` and `addendum.csv`:

```bash
python catalog/build.py
```

### Simulated Seestar

Tasks that touch the equipment are checked on a phone against OmniSim set up like the Seestar S50 Pro. The real Seestar is only needed for what OmniSim cannot copy, such as a night under the sky.

1. Start OmniSim so that the phone can reach it: `ascom.alpaca.simulators --urls=http://*:32323`. On Windows, allow it through the firewall when asked.
2. In its Driver Setup page (`http://localhost:32323/setup`):
   - Driver Settings: 2 focusers, and no domes, rotators or safety monitors.
   - Alpaca Discovery Settings: turn off "Respond to localhost on loopback only", so that discovery also answers the phone.
3. In each device's Setup page:
   - **Camera:** 2160 × 3840 pixels of 2.9 µm, maximum ADU 65535, sensor type RGGB with Bayer offset X 1 and Y 0 (so GRBG), maximum binning 1, no shutter or cooler, gain from 0 to 600, exposures up to 2,000 s.
   - **Focuser 0:** absolute, 0 to 2,600 steps, with temperature. **Focuser 1:** 0 to 1,023 steps.
   - **Filter wheel:** 3 slots named Dark, IR and LP, with no focus offsets.
   - **Switch:** one switch named "Dew heater", writable, from 0 to 1 in steps of 1.
   - **Telescope:** polar alignment mode, topocentric (JNow) coordinates, maximum slew rate 6° per second, no side of pier.
4. On the phone, on the same WiFi, Yacana finds it as "ASCOM Alpaca Simulators".

OmniSim cannot copy everything: device names stay OmniSim's (so Yacana makes a generic profile, not the Seestar's), images are a synthetic picture sent as bytes, and members the Seestar lacks still answer. Save OmniSim's settings folder (`~/.ASCOM/Alpaca/ASCOM-Alpaca-Simulator`) before changing it, and put it back before running the integration tests.

OmniSim's camera sends noise, so features that need stars (HFR, plate solving) are tried with `tools/replay-camera/replay_camera.py`: an Alpaca camera that answers each exposure with the next frame of a folder of 16-bit FITS files, such as real Seestar frames, and answers Alpaca discovery. Add it to a profile by IP and port (port 32324 by default), with OmniSim's mount if the mount's position should be the plate solving hint. It needs numpy.

```bash
python tools/replay-camera/replay_camera.py frames/
```

For plate solving on the emulator or the phone, download a star database in Settings · Plate solving, or import ASTAP's ZIP there. Each database is a folder of the app's, such as `Android/data/app.yacana/files/astap/d50/` with the `d50_*.1476` files, so one already unzipped can also be copied there with `adb push`.

To try autofocus, run it with `--focus` and the position of best focus: the server then has a focuser too, of 0 to 2600 steps as the Seestar's, and each frame is blurred by how far that focuser is from best focus (`--blur`, 3.5 pixels per 100 steps by default), so the stars' HFR draws a V. The focuser starts 60 steps out of focus. Add the focuser to the profile with the camera.

```bash
python tools/replay-camera/replay_camera.py frames/ --focus 1300
```

The sky map reads N.I.N.A.'s framing caches from `Android/data/app.yacana/files/sky/`, where Settings · sky map downloads them from N.I.N.A.'s site. A copy of `FramingAssistantCache_Full.zip` downloaded once on the PC goes there faster over USB, and opens the next time the app starts:

```bash
adb push FramingAssistantCache_Full.zip /sdcard/Android/data/app.yacana/files/sky/
```

The replayed sky does not move with the mount. To try sync and centring, serve a folder with a single frame, solve it in Imaging and sync the mount: "Go and centre" to the mount's position then centres in one attempt, and a target a few arcminutes away ends after 5 attempts with how far it stayed.

## Project layout

| Path | What lives there |
| --- | --- |
| `app/` | Android entry point: session service, permissions and platform code |
| `shared/ui/` | Compose Multiplatform UI |
| `shared/domain/` | Sequencer, equipment, polar alignment, autofocus and planning |
| `shared/alpaca/` | Alpaca client in Kotlin |
| `shared/astronomy/` | Astronomy Engine's source, as its author writes it: the Sun and the Moon (see its README) |
| `shared/ports/` | Interfaces of the pixel engine, the solvers and the platform |
| `engine/` | Rust pixel engine (crate and UniFFI bindings) |
| `catalog/` | The script that makes the object catalogue from OpenNGC |
| `docs/design/` | The style guide and the kit to draw new screens |
| `docs/manual/` | The user manual, in the app's ten languages |
| `tools/` | Small scripts for development and CI |

Two rules shape the code ([`ARCHITECTURE.md`](ARCHITECTURE.md)):

- `shared/` common code uses no Android APIs. It is tested on the PC.
- Pixels stay in native memory, in the Rust engine. Kotlin handles image handles and small results.

## Workflow

- Work on a short-lived branch from `main`, named `type/short-topic` (for example `feat/alpaca-discovery`).
- Write commits in [Conventional Commits](https://www.conventionalcommits.org/) style: `feat:`, `fix:`, `docs:`, `test:`, `build:`, `ci:`, `refactor:`, `chore:`.
- Open a pull request. It says what it changes and why, and needs green CI and a review. A maintainer can turn on GitHub's auto-merge, so it merges itself once CI is green.
- Releases follow [SemVer](https://semver.org/).

### Releases

A tag such as `v1.1.0` starts the Release workflow. It builds the release APK signed with the project's key, checks its 16 KB alignment, size and signature, and attaches it to a GitHub Release. The versionCode comes from the tag (`1.2.3` gives 10203), so each release installs over the previous one.

Before tagging, set the new version in the user manual (its title and its last line, in every language), in the last PR merged before the tag.

The key and its passwords live in the repository's secrets. Only the key's owner sets them, once, from the same `keystore.properties` that signs local release builds:

```bash
bash tools/set_release_secrets.sh
```

The script never prints a value: each one goes to `gh secret set` through a pipe, so passwords stay out of the screen and the shell history.

### Budgets

- **APK size:** 40 MB at most. CI checks it with `tools/check_apk_size.py`.
- **Cold start:** 2 s at most. With the release build installed on a phone, `python3 tools/cold_start.py` reports the median of 10 starts.

### Definition of done

- Tests are green, cover the new case, and there are no lint warnings.
- If the UI changed, it follows the [style guide](docs/design/STYLE.md): a new screen or a redesign is drawn with its kit and agreed first, and its reference screenshots have been reviewed in red night mode and in landscape.
- `ARCHITECTURE.md` and the user manual are updated when they apply.

## Code style

- Kotlin uses Android Studio's formatting style (`.editorconfig`), checked by ktlint and detekt. `./gradlew formatKotlin` fixes most findings.
- Rust uses `rustfmt` and `clippy`, with warnings as errors.
- Every source file starts with the MPL-2.0 header:

  ```kotlin
  // This Source Code Form is subject to the terms of the Mozilla Public
  // License, v. 2.0. If a copy of the MPL was not distributed with this
  // file, You can obtain one at https://mozilla.org/MPL/2.0/.
  ```

- Code ported from N.I.N.A. or its polar alignment plugin (both MPL-2.0) keeps its original copyright notice above that header.

## Decisions

A decision someone might want to reopen goes into [`ARCHITECTURE.md`](ARCHITECTURE.md), with its reasons.

## Languages

Code, comments, commits, issues and the repository's documentation are in English. The app ships in ten languages: English (the base language), Spanish, Catalan, Basque, Galician, German, French, Italian, and European and Brazilian Portuguese. A new text goes into every `strings.xml`; `tools/check_translation.py` and `TranslationsTest` check them.

## Test data and secrets

- Heavy test data, such as FITS files from the Seestar, stays out of git. It goes to GitHub Releases or Zenodo.
- Never commit signing keys or passwords. `keystore.properties`, `*.jks` and `*.keystore` are ignored by git.

## Security

A security problem is reported privately, as [SECURITY.md](SECURITY.md) says, never in a public issue.

## Conduct

Everyone taking part follows the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

Yacana is licensed under the [Mozilla Public License 2.0](LICENSE). By contributing, you agree that your contributions are licensed under it too.
