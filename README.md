# Magpie

Android app that files your downloads. When a file finishes downloading,
Magpie pops up over whatever app is on screen with three suggested
destination folders — chosen instantly by local rules, upgraded in place by
the Claude API when a key is set. Tap one and the file moves there. Stray
screenshots are swept silently into the main Screenshots folder.

Single user, sideloaded (Oppo A5 Pro 5G / ColorOS 16), never Play Store.
Package: `com.magpie`.

## How it works

1. **Detection** — a foreground service (`service/WatcherService.kt`) watches
   `Download`, both `Screenshots` folders, and SD-card equivalents with
   `FileObserver`, firing on `CLOSE_WRITE`/`MOVED_TO` only. In-progress
   markers (`.crdownload`, `.part`, …), dotfiles, and zero-byte files are
   ignored, and a file counts as complete only after two size reads 500 ms
   apart agree.
2. **Grouping** — files completing within the debounce window (default 10 s,
   capped at 60 s total) share one popup (`domain/GroupDebouncer.kt`).
3. **Popup** — a `TYPE_APPLICATION_OVERLAY` window hosting a `ComposeView`.
   `overlay/OverlayLifecycleOwner.kt` provides the three view-tree owners a
   raw overlay ComposeView needs, or it renders nothing. The popup opens
   immediately with local suggestions (`domain/LocalSuggester.kt` — learned
   decisions, then extension matches, then preset recency/frequency); the
   Claude call runs in parallel with a 3 s timeout and swaps the buttons in
   place if it lands. No key / no network / cap reached / any error → local
   suggestions simply stay. If the overlay can't be shown (ColorOS
   suppresses it during calls), a heads-up notification with the three
   folders as action buttons takes over.
4. **Safety** — every path (including every path the model returns) passes
   `domain/PathGuard.kt`: nothing outside the storage roots, nothing hidden,
   nothing under `Android/`, destinations only from the preset list. Moves
   are copy → verify (length + SHA-256 under 50 MB) → rename → delete
   (`domain/SafeMover.kt`); name clashes get ` (2)`, ` (3)`…, failures are
   loud and leave the original untouched. Every move is logged with undo.
5. **API** — one raw Messages-API call per popup group
   (`api/ClaudeClient.kt`, OkHttp + kotlinx.serialization; model default
   `claude-haiku-4-5-20251001`, editable). Costs are computed from the
   `usage` block at editable per-MTok rates; a monthly spend cap (default
   $2.00) is a hard stop, after which everything runs on local rules with a
   banner on the home screen. The key is Tink-encrypted (AEAD keyset wrapped
   by an Android Keystore master key — deliberately *not* the deprecated
   `EncryptedSharedPreferences`), ciphertext in DataStore, never logged.

Everything works with no API key: detection, popup, local suggestions,
moves, log, undo, screenshot sweep, backlog sort, and the preset wizard
(minus AI descriptions).

## Building

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

minSdk 26, target/compileSdk 36, Kotlin 2.2, AGP 8.11, Compose BOM
2025.06.01, Room + KSP, DataStore, Tink (see `gradle/libs.versions.toml`).

CI (`.github/workflows/build.yml`) builds the debug APK on every push and
uploads it as the `magpie-debug-apk` artifact — download it on the phone and
sideload. The checked-in `keystore/debug.keystore` keeps the signature
stable across CI runs so new builds install over old ones.

## Deliberate deviations from the build prompt

- The foreground service uses FGS type `specialUse` rather than `dataSync`:
  Android 15+ forbids starting `dataSync` services from `BOOT_COMPLETED`,
  and surviving reboot is an acceptance test. The `FOREGROUND_SERVICE_DATA_SYNC`
  permission is still declared.
- "Recursive where needed" watching is implemented as each watched folder
  plus its immediate subfolders (covers `Download/<app>/` writers without
  watching the whole tree).

## Icon

Placeholder teal line-art magpie on near-black
(`res/drawable/ic_launcher_foreground.xml`) until the real artwork lands;
adaptive + monochrome layers wired.
