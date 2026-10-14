<div align="center">

<img src="yacana-banner.png" width="420" alt="Yacana logo">

</div>

# Yacana brand

The logo is a dark llama whose only bright part is its eye, a gold star, seen against the Milky Way.

Yacana is the llama of Inca astronomy: a dark cloud constellation in the Milky Way, between Scorpius and the Southern Cross. Its eyes are Alpha and Beta Centauri, its only bright part. The logo keeps exactly that: the llama is dust, the eye is a star, and the Milky Way is the light that makes the silhouette visible. The gold of the eye is also the app's accent color.

## Files

| File | Use |
| --- | --- |
| `yacana-icon.svg` | Round app icon: README, website, avatars |
| `yacana-lockup.svg` | Icon and name, for dark backgrounds |
| `yacana-lockup-light.svg` | Icon and name, for light backgrounds |
| `yacana-lockup.png`, `yacana-lockup-light.png` | The two above at 1,376 × 400 pixels, as PNG |
| `yacana-banner.png` | The lockup on a rounded card (1,600 × 520), readable on light and dark backgrounds: the READMEs' header, as GitHub's mobile app shows neither SVG nor `<picture>` |
| `yacana-mark.svg` | One-color silhouette with the eye cut out; recolor as needed |
| `yacana-icon-red.svg` | Red night mode version, for use inside the app |
| `android/ic_launcher_background.svg` | Adaptive icon background layer (108 × 108 dp): sky, Milky Way and stars |
| `android/ic_launcher_foreground.svg` | Adaptive icon foreground layer (108 × 108 dp): llama and eye |
| `android/ic_launcher_monochrome.svg` | Themed icon layer (Android 13+) |
| `android/ic_stat_yacana.svg` | Notification icon (24 × 24 dp) |

The Android files are sources. The app's vector drawables in `app/src/main/res/drawable/` are made from them: edit the SVG first, then the drawable.

## Colors

| Role | Hex |
| --- | --- |
| Night sky | `#0B0F14` to `#162131` |
| Milky Way | `#F1E6CF` |
| Llama | `#05070A` |
| Eye (app accent) | `#E8B04A` |
| Red night mode | `#FF0000` on `#000000` |

## Wordmark

The name is set in [Jost](https://github.com/indestructible-type/Jost) Medium (weight 500) and converted to outlines, so the files do not need the font. Jost is under the SIL Open Font License 1.1.

## Rules

- One eye, always gold (red in red night mode).
- The llama stays dark. Never draw it as a gold or light outline: that is the look of the ASCOM Alpaca logo.
- For one-color uses (notifications, themed icons, single-ink print), use the silhouette with the eye cut out.
- In red night mode the logo is red on black, like every other pixel.
- Keep clear space around the icon of at least a quarter of its diameter.
