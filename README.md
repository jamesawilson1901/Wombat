# Wombat

Two unrelated Android apps live in this repo, as separate Gradle modules:

| Module | App | What it does |
| --- | --- | --- |
| `:app` | **Wombat** | Splits a large folder or file into parts under a size limit. Documented below. |
| `:magpie` | **Magpie** | Watches your download folders and offers to file each new arrival. See [`magpie/README.md`](magpie/README.md). |

Magpie needs two things set up on first install or it will quietly stop working:
all-files access, and ColorOS battery whitelisting (Settings → Battery → App
battery usage → Magpie → Allow background activity, Optimise battery use off,
and lock it in recent apps). The full instructions and the honest list of its
limits are in [`magpie/README.md`](magpie/README.md).

---

## Wombat

Android app that splits a large folder (or a single file) into parts that
each stay under a storage limit (e.g. 25 MB). Package: `com.wombat.split`.

## How it works

1. Pick a folder or a single file to split, a destination folder (Storage
   Access Framework — no storage permission needed), a part size limit in
   MB (default 25), and whether to zip each part (default on).
2. Wombat packs whole files into parts with first-fit-decreasing bin
   packing (`split/SplitPlanner.kt` — pure logic, unit-tested), keeping
   every part under the limit and preserving each file's relative path.
   Files bigger than the limit are byte-chunked into `.001`/`.002` slices,
   one slice per part; reassemble with `cat name.* > name` (chunks stay
   raw even in zip mode — a zip of a partial slice would add nothing).
3. Parts are **copied** (the source is never modified) into the
   destination, named after the job: `echidna_01.zip`, `echidna_02.zip`, …
   (or folders `echidna_01/`, … with zip off). Jobs auto-name from the
   animal pool.
4. Jobs run in a foreground service with a progress notification and a
   working Cancel action, so they survive the app going to the background.
   Job history is persisted and survives app restarts; jobs interrupted by
   process death reload as failed.

CI (`.github/workflows/build.yml`) builds the debug APK on every push to
main and uploads it as the `app-debug-apk` artifact.

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
