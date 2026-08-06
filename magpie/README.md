# Magpie

Android app that watches the folders downloads land in. When a file finishes
arriving, Magpie posts a notification. Tapping it opens the system folder
picker, offers to tidy the name, and moves the file there. Anything you do not
deal with waits in a list inside the app.

It exists because Android gives you no way to intercept a browser download and
ask where to save it. Magpie catches the file a second after it lands instead.

Package: `com.magpie.filer`. Second app in this repo — Wombat (`:app`) is
unrelated and untouched.

## First install, on ColorOS

Two things, in this order. Neither is optional.

**1. All-files access.** Open Magpie and tap *Grant all-files access*. Magpie
cannot see what lands in Downloads without it, and cannot delete an original
after copying it.

**2. Stop ColorOS killing the watcher.** Settings → Battery → App battery usage
→ Magpie → **Allow background activity**, and turn **Optimise battery use**
off. Then open the recent apps view, find Magpie, and **lock** it (drag down on
the card, or the padlock, depending on the build). Without all three the
service is killed within a day and Magpie silently stops noticing files.

If the service is killed anyway, the main screen says so the next time you open
the app: the watch toggle reads *"On, but the background service is not
running"*. Turn it off and on again to restart it.

## What it watches

- Internal `Download`
- `Pictures/Screenshots` **and** `DCIM/Screenshots` — which one exists varies by
  manufacturer, so both are watched
- A removable card's `Download` folder, if one is mounted

The card is found at runtime from the mounted storage volumes, so there is no
hardcoded path. Insert a card while Magpie is running and it starts watching it
within about fifteen seconds; eject it and it stops, without complaint.

Bluetooth and app media folders (WhatsApp and so on) are deliberately **not**
watched.

## How it decides a file has finished arriving

There is no API for this, so it is inferred:

- A `FileObserver` on each folder means only *look again soon* — under scoped
  storage these events are unreliable, so nothing depends on them.
- A periodic sweep is the real mechanism: every 2.5 seconds while something is
  mid-download, every 15 seconds otherwise.
- Anything with a temporary extension is skipped: `.crdownload`, `.part`,
  `.partial`, `.tmp`, `.download`, `.opdownload`, `.!ut`, and any name starting
  with a dot.
- A file counts as finished when its size is non-zero and unchanged between two
  consecutive looks at least ~2.2 seconds apart.

When Magpie starts watching a folder for the first time, everything already in
it is written off as old, so switching Magpie on does not produce a hundred
notifications. Those files are still reachable — they are simply not offered.

## Moving a file

Every move is **copy, verify, then delete**. There is no raw move anywhere in
the code, and no path on which the original is deleted before the copy has been
checked:

1. The bytes are copied into a freshly created document in the chosen folder and
   flushed to disk.
2. Three things are checked: the number of bytes written matches the source, the
   size the destination reports back matches the source, and the source has not
   changed size while the copy was running.
3. Only then is the original deleted.

If any check fails, the part-written copy is removed and the original is left
exactly as it was — and you are told which check failed and why. If the copy is
verified but the original cannot be deleted, you are told plainly that two
copies now exist and where the other one is.

## Notifications

One per file, never grouped. Each shows the filename, the size and the file
type, and clears itself after four minutes if it is ignored. **Dismissing a
notification never dismisses the file** — it stays in the waiting list. There is
also a quiet, permanent notice while watching is on, with a *Stop watching*
button.

## Renaming

After you pick a folder, Magpie offers the original name pre-filled and
editable, plus one or two tidied variants: separators turned back into spaces,
query strings and URL escapes dropped, hash- and id-shaped runs removed, and
capitalisation evened out. The extension is always kept. Suggestions are built
**only from the filename** — never the date, the site it came from, or the
folder you are filing into. If a tidied name comes out identical to the
original, it is not offered twice.

Renaming is skipped for batch moves.

## No history, no undo

Deliberately out of scope. Magpie keeps a waiting list, an ignored list, and the
set of files it has already seen. It does not keep a log of what it moved.

## Known limits, honestly

- **Files that arrive while the service is dead are treated as old.** When the
  service restarts it re-baselines every watched folder, so anything that landed
  in the meantime is never offered. Nothing is lost — those files are sitting in
  Downloads exactly where the browser put them — but you will have to file them
  by hand. This is the price of not flooding you with notifications after a
  restart.
- **Only the top level of each folder is watched**, not subfolders.
- **Android may still group notifications** once several are showing. Magpie
  sets no group itself, but the system's own bundling is out of its hands.
- **A move into the folder the file is already in** does nothing if the name is
  unchanged, and behaves as a rename if the name is different.
- If the destination provider does not report a size back, verification falls
  back to the byte count Magpie wrote, and the outcome says so.

## Building

CI (`.github/workflows/magpie.yml`) builds the debug APK on every push to `main`
and to `claude/**`, runs the unit tests, and uploads the APK as the
`magpie-debug-apk` artifact. Gradle is installed by
`gradle/actions/setup-gradle`, so no wrapper jar is needed for this build:

```
gradle :magpie:assembleDebug
gradle :magpie:testDebugUnitTest
```

minSdk 30 (all-files access needs API 30), targetSdk 35, compileSdk 36,
Kotlin 2.2, AGP 8.11, Compose BOM 2025.06.01.

Unit tests cover the parts worth testing without a device: filename tidying and
suggestion building, size and file-type wording, and the finished-arriving rule.

## No network

Magpie makes no network calls of any kind — no analytics, no crash reporting, no
update checks, no accounts. It does not request the `INTERNET` permission, so
the platform itself would refuse one. It works fully offline.

## Permissions, and why

| Permission | Why |
| --- | --- |
| `MANAGE_EXTERNAL_STORAGE` | Read the watched folders, and delete an original after a verified copy. Scoped storage can do neither for files Magpie did not create. |
| `POST_NOTIFICATIONS` | Offer each file as it lands. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | Keep watching while the app is closed. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | From Android 15, a `dataSync` foreground service is capped at six hours a day, which would stop the watcher mid-day with no warning. `specialUse` has no cap. Both types are declared; `specialUse` is used from API 34 up. |
| `RECEIVE_BOOT_COMPLETED` | Resume watching after a reboot, but only if it was on. |

Destinations are written through the Storage Access Framework
(`OPEN_DOCUMENT_TREE`), not raw paths, so filing onto an SD card works properly.

## Icon and brand

The launcher icon and the in-app tile are both cut from the supplied artwork
(`design/magpie-tile-source.jpg`) by `scripts/render-magpie-icons.py` — nothing
is hand-placed:

| Layer | Source | Notes |
| --- | --- | --- |
| `mipmap-*/ic_launcher_foreground.png` | the white mark from the artwork | Scaled so every inked pixel falls inside the 66dp safe circle, so no launcher mask crops the bird or the wordmark |
| `mipmap-*/ic_launcher_background.webp` | the artwork's circuit field, from outside the tile frame | Muted toward the tile's own background tone so it reads as texture, not decoration |
| `mipmap-*/ic_launcher_monochrome.png` | the bird alone | Themed icons on Android 13+ |
| `drawable-*/ic_stat_magpie.png` | the bird, tight-cropped | Status bar and notifications |
| `drawable-nodpi/magpie_tile.webp` | the whole tile, corners rounded | The header at the top of the main screen |

`design/magpie-icon-preview.png` shows the composed icon under a circular and a
squircle mask. `art/magpie-ic_launcher-playstore.png` is the flat 512px square.

To regenerate after editing the artwork: `python3 scripts/render-magpie-icons.py`
(needs Pillow).

**Deviation from the original spec, on purpose:** the spec asked for a bird-only
launcher icon with no wordmark and no background pattern. The supplied artwork
was explicitly to be used as the app tile, so the wordmark and the field are
both in the icon. Everything still survives masking. To go back to bird-only,
point `<foreground>` in `res/mipmap-anydpi-v26/ic_launcher.xml` at
`@mipmap/ic_launcher_monochrome` — that asset is already the bird alone, fitted
to the same safe circle.

## Theme

Follows the system light/dark setting, with both schemes finished. Fixed
silver-grey palette, no Material You, no bright colour anywhere — the only
colour in the app is the muted rust used for an error you need to read.

- Dark: background `#12141A`, surfaces `#171A21`–`#262A33`, text `#E6E9EE`,
  accent `#C9CED6`
- Light: background `#F7F8FA`, surfaces `#FFFFFF`–`#E6E8EC`, text `#14161B`,
  accent `#5A626E`

Palette in `ui/theme/Color.kt`, schemes in `ui/theme/Theme.kt`.
