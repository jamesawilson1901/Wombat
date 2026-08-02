# Wombat

Android app for split jobs. Package: `com.wombat.split`.

## Brand

The launcher icon sets the brand: **silver line-art wombat on near-black**
(`#16161D` tile, `#CCCCCE` line — both sampled from the source artwork).
Everything in the UI follows from it.

- **Dark-first fixed theme.** `WombatTheme` (Compose Material 3) uses a fixed
  palette — near-black surfaces, silver/light-grey accents — with **no
  Material You / dynamic colour**, so the app matches its icon on every
  device. A light theme is included with the same silver-on-neutral
  character; the app follows the system dark/light setting.
- Palette lives in `app/src/main/java/com/wombat/split/ui/theme/Color.kt`.

## Launcher icon

Adaptive icon, fully vector — no density PNGs needed (minSdk 26 means every
device uses the adaptive icon):

| Layer | File | Notes |
| --- | --- | --- |
| Foreground | `res/drawable/ic_launcher_foreground.xml` | Wombat outline only, transparent bg, scaled inside the 66dp safe zone |
| Background | `@color/ic_launcher_background` | Flat `#16161D` — no baked corners or shadows; the launcher masks and elevates |
| Monochrome | `res/drawable/ic_launcher_monochrome.xml` | Themed icons on Android 13+ |

Source artwork:

- `design/icon-source.png` — the original rendered artwork (silver wombat on
  a near-black rounded tile). This is the source of truth.
- `design/icon-source.svg` — vector master, hand-traced from the artwork
  (108×108 adaptive-icon canvas); the drawables derive from it.
- `design/icon-preview.png` — 1024px render of the vector trace.
- `art/ic_launcher-playstore.png` — 512px flat square for the Play listing.

To regenerate the PNGs after editing the SVG: `scripts/render-icons.sh`
(needs any Chromium/Chrome; set `CHROME=` to point at one).

## Job auto-naming

New jobs are auto-named from an animal pool (`JobNames`, in
`app/src/main/java/com/wombat/split/jobs/JobNames.kt`). The pool starts at
**echidna**; **wombat is deliberately excluded** — it's the app's name, not a
job name. After a full lap the names wrap with a round suffix
(`echidna-2`, …). Unit tests: `app/src/test/.../JobNamesTest.kt`.

## Building

Standard Android Gradle build:

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

- minSdk 26, targetSdk/compileSdk 36, Kotlin 2.2, AGP 8.11, Compose BOM
  2025.06.01 (see `gradle/libs.versions.toml`).
