# Wombat

Android app that splits a large folder into part folders that each stay
under a storage limit (e.g. 29 MB). Package: `com.wombat.split`.

## How it works

1. Pick a folder to split and a destination folder (Storage Access
   Framework — no storage permission needed), set a part size limit in MB
   (default 29).
2. Wombat scans the folder, packs whole files into parts with
   first-fit-decreasing bin packing (`split/SplitPlanner.kt` — pure logic,
   unit-tested), keeping every part under the limit and preserving each
   file's relative path.
3. Parts are **copied** (the source is never modified) into sibling folders
   named after the job: `echidna-01`, `echidna-02`, … Jobs auto-name from
   the animal pool.
4. Files bigger than the limit can't fit in any part; each gets its own
   part and the job reports a warning.

Current v1 limitations: jobs are in-memory only (no persistence across app
restarts) and run while the app is in the foreground.

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
