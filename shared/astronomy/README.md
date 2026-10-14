# Astronomy Engine

The ephemerides of the Sun and the Moon, their rise and set, and twilight come from
[Astronomy Engine](https://github.com/cosinekitty/astronomy) by Don Cross, under the MIT licence
that heads its source file.

`astronomy.kt` is the Kotlin source of release v2.1.19, as is, but for one change: `DateTime.toString()`
pads its numbers itself instead of calling `String.format`, which only the JVM has, so that the file
builds for every Kotlin target. The change is marked "Yacana:" in the file.

The author publishes the Kotlin version only on JitPack. Keeping the one file here means the build
needs no extra repository, and the code stays as its author wrote it: Yacana's linters skip this module.
