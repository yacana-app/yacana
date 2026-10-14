# ASTAP command-line solver

Yacana solves images with [ASTAP](https://www.hnsky.org/astap.htm) by Han Kleijn, run on the phone as a separate process. ASTAP is licensed under the [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/), like Yacana. Its source code is at [sourceforge.net/projects/astap-program](https://sourceforge.net/projects/astap-program/) and [github.com/han-k59/astap](https://github.com/han-k59/astap).

## The binaries

| File | ABI | SHA-256 |
| --- | --- | --- |
| `android/arm64-v8a/libastap_cli.so` | Phones (aarch64) | `a67eec027a09ddacad18a09de54dc7769b3bbd48cadbc573cb243b5b3acb0cdf` |
| `android/x86_64/libastap_cli.so` | Emulator: the same aarch64 build | `a67eec027a09ddacad18a09de54dc7769b3bbd48cadbc573cb243b5b3acb0cdf` |

- **Source:** the official Android build `astap_command-line_version_Android_aarch64.zip`, from the project's `android` folder on SourceForge (published 2026-09-19).
- **Version:** the program reports `CLI-2026.09.01`.
- **Changes:** none. The `astap_cli` executable is renamed to `libastap_cli.so`, as its author recommends. Android then installs it with the native libraries, the only place from which an app may run a program.
- **16 KB pages:** the build already has 16 KB aligned segments, which `tools/check_16kb.py` checks in CI. It is used as published. Building from source is only needed for F-Droid.
- **Emulator:**
  - The official x86_64 build has text relocations (`DT_TEXTREL`), and Android refuses to run it from an app ("CANNOT LINK EXECUTABLE … has text relocations").
  - The x86_64 emulator images of Android 11 and later run aarch64 programs through their ARM translation (`libndk_translation`). So the x86_64 folder carries the aarch64 build.
  - This only matters for development: phones are arm64.

The app module adds this folder to its native libraries and extracts them at install time (`useLegacyPackaging`). It runs the solver from `applicationInfo.nativeLibraryDir`.

## Updating

1. Download the new `astap_command-line_version_Android_aarch64.zip` from the `android` folder of the project on SourceForge.
2. Copy its `astap_cli` over both `libastap_cli.so` files.
3. Run `python3 tools/check_16kb.py solvers/astap/android`.
4. Update the version and the SHA-256 values above.
5. Solve a few real frames on a phone, with and without a hint, before merging: about 0.4 s with a hint and 7 s blind with D50 on a recent phone.

## Star databases

The databases are not in the repository. The app reads them from the folder of each profile. D50 is the default for the Seestar: the densest that solves its frames within the time and space targets, with margin for poor nights.
