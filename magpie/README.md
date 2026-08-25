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

When Magpie starts watching a folder, it records the time and treats everything
older than that as old, so switching Magpie on does not produce a hundred
notifications. Those files are still reachable: **Already in your folders** on
the main screen lists what is in the watched folders now, and you can file any
of it by hand.

The baseline is a timestamp per folder, not a list of filenames. That matters in
three places: it works for a folder with ten thousand files in it, it survives a
card being taken out and put back, and it survives the service being killed — so
a file that arrived while Magpie was dead is still offered when it comes back.
Turning watching off and on again starts the baseline afresh from that moment.

If a lot of files turn up at once, all of them go on the waiting list but only
the first five in each sweep get a notification. Coming back from a day of being
killed should not bury your notification shade.

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

## What was actually run, and what was not

Being straight about this, because it changes how much you should trust it on
first install:

- **Compiled and tested on CI, every push.** `gradle :magpie:assembleDebug`
  produces the APK and `gradle :magpie:testDebugUnitTest` runs green. The unit
  tests cover filename tidying and suggestions, size and file-type wording, and
  the finished-arriving rule.
- **Never run on a device or an emulator.** Nothing in this app has been
  executed on Android. Everything below the pure-Kotlin layer — the watcher, the
  foreground service, the notifications, every move, and the whole UI — is
  reviewed and reasoned about, not observed working.

What that means in practice: the first thing to try is one small file, into a
folder on internal storage, and check the copy arrived before trusting it with
anything that matters. Then try one onto the SD card.

- **No request has ever been made to Anthropic from this code.** The suggestion
  path compiles, and the SDK call is built against the published API, but it has
  not been run once — not on a device, not on a desktop, not with a real key.
  The first suggestion you ask for is the first time that code executes.

The specific things worth watching for, because they are the least certain:

- **The `specialUse` foreground service type.** If ColorOS objects to it, the
  service will not start and the toggle will say so. `dataSync` is also declared,
  so switching `goForeground()` in `WatchService.kt` back to
  `FOREGROUND_SERVICE_TYPE_DATA_SYNC` is a one-line fallback.
- **Whether ColorOS lets the service live at all**, even with the battery
  settings above. This is the risk the whole app rests on and it cannot be
  checked from here.
- **SD card discovery.** Volume IDs and the `Download` vs `Downloads` spelling
  vary by manufacturer; both are tried, but only a real card proves it.
- **Which storage provider your file manager uses for the picker.** The
  same-folder check and the "do not re-offer our own copy" logic only understand
  Android's own storage provider. With anything else they quietly do nothing —
  the move still works and is still verified.
- **Whether `DocumentsContract.createDocument` keeps your extension** for a file
  type outside the built-in table. If a provider appends its own, the outcome
  message tells you the name it actually used.
- **The Anthropic SDK on Android.** It is a Java library built for a server, not
  an Android one: it pulls in OkHttp and Jackson, and its jar is merged into the
  APK. It compiles, and it needs nothing above API 30, but a class-loading
  failure at runtime is the plausible way this breaks. The one place it is
  called catches `Throwable` for exactly that reason, so a failure costs you the
  suggestion and nothing else.
- **Release builds are unproven with it.** `isMinifyEnabled` is on for release
  and `proguard-rules.pro` now carries keep rules for the SDK and Jackson, but
  CI only ever builds the debug APK, so no shrunk build has been produced.

## Known limits, honestly

- **Only the top level of each folder is watched**, not subfolders.
- **Android may still group notifications** once several are showing. Magpie
  sets no group itself, but the system's own bundling is out of its hands.
- **A move into the folder the file is already in** does nothing if the name is
  unchanged, and behaves as a rename if the name is different.
- If the destination provider does not report a size back, verification falls
  back to the byte count Magpie wrote, and the outcome says so.
- **Files on a card that has been taken out stay on the lists.** Magpie will not
  drop an entry unless it can read the folder it lived in and see that the file
  has gone, because guessing the other way would empty your lists every time a
  card was unplugged.
- **Already in your folders** lists the top level of each watched folder, newest
  first, up to sixty entries. It is a way to reach a file, not a file manager.

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

## Naming suggestions, and the one thing that leaves the phone

This is the only part of Magpie that touches the network, and it is off until
you switch it on.

**Turning it on.** Settings, in the app: paste an Anthropic API key, flick
*Suggest names and folders*, and — optionally — pick a **library folder**. The
library folder is the one your filing folders live inside; Magpie lists the
folders directly inside it and those are the only destinations Claude is allowed
to choose between.

**What is sent, per file:** the filename, its size, its type, the name of the
folder it landed in, and the names of the folders in your library. That is all.
**The file itself is never opened, let alone sent.** Magpie has no code that
reads a file's contents for this — it only ever copies bytes from one place to
another when you file something.

**What comes back:** a proposed name, one of the folder names you offered (or
none), and one sentence saying why. You get a *Use this* / *Choose myself*
choice; nothing moves until you have picked a folder in the system picker, and
the proposed name lands in the rename box pre-filled and editable, exactly like
a locally tidied one.

**What is not trusted.** Claude's answer is put back through the same sanitising
a hand-typed name gets, and the extension is reattached from the original, so a
bad reply cannot produce a path separator, a hidden dotfile, or a changed file
type. A suggested folder is only accepted if it matches a folder Magpie actually
listed — an invented folder name is discarded and you pick by hand.

**Where the key lives.** In Magpie's own `SharedPreferences`, private to the app
and unreadable by any other app. It is never logged and never sent anywhere
except as the authorisation header on the request to Anthropic. It is not
encrypted at rest, so a rooted phone or a full-device backup would expose it;
`allowBackup` is off, which covers the ordinary case. Delete it by clearing the
field.

**Cost.** Billed to your own Anthropic account, not to anything of Magpie's. One
request per file you tap, using `claude-opus-5` at low effort with a 2,048-token
cap; the prompt is a few hundred tokens and the reply is a few dozen. Nothing
runs in the background — no request is ever made unless you tapped a file.

**Failures never block filing.** No key, no network, a rejected key, a rate
limit, a refusal, a reply Magpie cannot read: each one becomes a sentence in the
app saying what actually happened, and the folder picker opens anyway. While it
is waiting, the dialog has a *Skip and file it myself* button, so a slow
connection costs you a tap rather than a minute. A suggestion is a convenience,
and it is never the thing standing between you and your file.

**What it has never been told.** The prompt gets the filename and nothing more,
so a suggestion cannot know the date, the site the file came from, or what is
inside it — the same rule the local tidying follows. If it guesses at any of
those from the name alone, it is guessing, and the one-line reason is there so
you can see whether it was.

## Working offline

With suggestions switched off — the state it installs in — Magpie makes no
network calls of any kind. No analytics, no crash reporting, no update checks,
no accounts, and nothing in it phones home. Everything except the suggestion
step works with the phone in aeroplane mode, and the suggestion step degrades to
a visible "could not reach Anthropic" and the ordinary flow.

Magpie does declare the `INTERNET` permission, because the platform would
otherwise refuse the request even when you have asked for it. This is a
deliberate reversal of the original design, which forbade the permission
outright; it was reversed on request, to add this feature.

## Permissions, and why

| Permission | Why |
| --- | --- |
| `MANAGE_EXTERNAL_STORAGE` | Read the watched folders, and delete an original after a verified copy. Scoped storage can do neither for files Magpie did not create. |
| `POST_NOTIFICATIONS` | Offer each file as it lands. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | Keep watching while the app is closed. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | From Android 15, a `dataSync` foreground service is capped at six hours a day, which would stop the watcher mid-day with no warning. `specialUse` has no cap. Both types are declared; `specialUse` is used from API 34 up. |
| `RECEIVE_BOOT_COMPLETED` | Resume watching after a reboot, but only if it was on. |
| `INTERNET` | One request to Anthropic per file you tap, and only with naming suggestions switched on and your own API key saved. Nothing else in the app uses it. See [Naming suggestions](#naming-suggestions-and-the-one-thing-that-leaves-the-phone). |

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
