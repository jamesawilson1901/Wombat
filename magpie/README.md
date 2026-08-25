# Magpie

Android app that watches the folders downloads land in. When a file finishes
arriving, Magpie posts a notification. Tapping it opens the system folder
picker, offers to tidy the name, and copies the file there — the original is
never deleted. Anything you do not deal with waits in a list inside the app.

It exists because Android gives you no way to intercept a browser download and
ask where to save it. Magpie catches the file a second after it lands instead.

Package: `com.magpie.filer`. Second app in this repo — Wombat (`:app`) is
unrelated and untouched.

## First install, on ColorOS

Two things, in this order. Neither is optional.

**1. All-files access.** Open Magpie and tap *Grant all-files access*. Magpie
cannot see what lands in Downloads without it: scoped storage will not show it
files it did not create itself. It is never used to delete anything.

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

## Sorting the memory card

Watching and sorting are two different things, and the card gets both.

**Watching** is narrow on purpose, because every watched folder means a
notification each time something lands in it. On a card that stays the
`Download` folder, as above.

**Sorting** is you going looking, so it reaches across the whole card. The
*Already in your folders* section lists what is on the card as well as in the
watched folders, so anything already sitting there can be filed: the card's
root, plus `Download`, `Downloads`, `DCIM`, `Pictures`, `Movies`, `Music`,
`Documents`, `Books`, `Podcasts`, `Recordings` and `Bluetooth`. Folders that do
not exist on your card are skipped in silence, so a card laid out any way works.

Bluetooth appears here but is still never *watched* — files arriving over
Bluetooth do not raise notifications, they are just reachable when you go
looking. Every one of these paths goes through the same safety check as
everything else, so a card is not a way around it.

The list is capped at sixty entries, newest first. It is a way to reach a file,
not a file manager.

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

## The fail-safe: Magpie never deletes anything

**There is no delete call anywhere in this app.** Not for your original, not for
a copy of its own that failed halfway, not for a folder, not for anything. This
is not a setting and there is no switch for it — the capability is simply not in
the code, and `MoveOutcome` has no "moved" case for a future change to reach
for.

What that means in practice:

- **Filing means copying.** The original stays exactly where it was. After a
  file is filed it comes off the waiting list, because you have dealt with it,
  and the report tells you the full path where the original still sits so you
  can remove it yourself once you are happy with the copy.
- **Nothing is ever written over.** If something of that name is already in the
  folder you chose, the copy goes into a `Duplicates` folder inside it instead —
  see below.
- **A failed copy is left where it fell.** If a check fails, the part-written
  file stays in the destination and you are told its exact name and folder.
  Removing it would be a delete, so Magpie will not do it for you.

The cost of this is real and worth stating: **your Downloads folder does not
empty itself.** Magpie tells you what it copied and where the original is, and
clearing up is yours to do. That is the trade the fail-safe buys.

### What is copied, and how it is checked

1. Both ends are put through the safety check below — before a single byte is
   read.
2. The destination is listed to see whether that name is already taken.
3. The bytes are copied into a freshly created document and flushed to disk.
4. Three things are checked: the number of bytes written matches the source, the
   size the destination reports back matches the source, and the source has not
   changed size while the copy was running.

Any check failing is reported with the reason, and nothing anywhere is removed.

## Duplicates

A duplicate goes into a **`Duplicates` folder inside the destination**, never
over the top of what is there and never left to the storage provider to rename
to `thing (1).pdf`.

Two things count as a duplicate:

- **The name is already taken.**
- **The same file is already there under a different name.** Contents are
  compared with a SHA-256 fingerprint, so this is a fact rather than a guess —
  and it is the case a name check would never have caught.

The report says which, and whether the contents actually match: the same file,
a different file with both sizes given, or that the contents could not be
compared. Three answers rather than a hedge.

This costs almost nothing. Two files can only match if they are the same size,
so the sizes already in the folder listing narrow the candidates before
anything is opened — usually to none, in which case nothing is read at all. A
folder that will not report sizes yields no candidates, which is the safe way
round: a duplicate goes unnoticed rather than a different file being called
one. A candidate that cannot be read is said so, not assumed either way.

An existing `Duplicates` folder is reused, never duplicated itself.

## Building a folder tree

Paste an indented tree into **Build a folder tree**, choose where it goes, and
Magpie makes it:

```
TRIAD_SHORT/
  01_CHARACTER_MASTERS/
  02_LOCATION_MASTERS/
  03_PROP_MASTERS/
  04_SHOT_STILLS/
    S01_ARRIVAL/
    S02_TOWN/
    S03_OLD_PATH/
  05_VIDEO_TESTS/
  06_VIDEO_FINALS/
  07_SOUND/
  08_EDITS/
```

Nesting comes from the indenting, and a line further in than the one above it
goes inside it. The width does not have to be consistent and tabs and spaces
can be mixed, because pasted text usually is. Trailing slashes are optional,
blank lines and `#` comments are skipped, and a line can carry its own slashes
(`04_SHOT_STILLS/S01_ARRIVAL`) and nest just the same.

**Only folders are ever made.** A folder that is already there is reused — not
replaced, not emptied, and everything in it is left alone. So running the same
tree twice makes nothing the second time, and building a tree over one you have
already started only fills in what is missing. No file is touched at all.

Before anything is made, the card shows every path it is about to create, so a
mis-indented paste is obvious then rather than afterwards. The report says what
was made, what was already there, and anything that failed with the reason.

Names go through the same cleaning a typed name gets: a name that would climb
out with `..`, or start with a dot, is refused outright, and illegal characters
inside an otherwise sensible name are stripped. There is a cap of 300 folders
and 10 levels, so a stray paste cannot run away.

## Safe to clear

The fail-safe means Downloads never empties itself, and the only thing between
you and clearing it is knowing which files are redundant. Magpie knows.

**Safe to clear** lists every original that has a copy Magpie checked byte for
byte, with where the copy went and the original's full path. Magpie still never
removes anything — you do, in your file manager — but it stops being the only
one who knows which files are safe to go.

Opening the list re-checks every entry, because a list that says "safe to
clear" has to be right or it is worse than useless. An original you have
already removed drops off; so does one whose copy has since been moved or
deleted by something else. A folder that cannot be read — a card that is out —
keeps its entries rather than guessing them away. *Done with it* drops one by
hand.

This is deliberately **not a history and not an undo log**. It records nothing
about what Magpie did, only where a redundant file is sitting right now, and
entries exist to be checked and then to go away. "No history, no undo" still
holds.

## Rules

Filing is repetitive — bank statements go to the same folder every month — so
after you file something Magpie offers to remember where that kind of file
goes.

A rule matches on the two things actually in a filename: its **extension** and
a **word** in it. Both are things you can look at and predict, and the rule
says in words exactly what it will do. A matching rule opens the folder picker
already at the right folder; it never files anything without you confirming.

What it saves is the choosing, the waiting and the cost. A rule answers
instantly, works offline and spends nothing, so the API is left for files that
are genuinely new.

Nothing is learned quietly in the background: a rule exists because you agreed
to one, it is listed under **Rules** with what it does, and *Forget* removes
it. Rules are tried in order, top first, and the offer is only made for a file
no rule already covers.

Two things the tests exist to prevent. A rule with neither an extension nor a
word would match everything and file the whole world into one folder — it
cannot be created, and one is dropped even if it somehow turns up in storage.
And the word comes from the filename alone, never a date or a number, so
`20240317_142233.jpg` yields an extension-only rule rather than one keyed on a
timestamp that will never recur.

## Folders you choose to watch

Downloads, both `Screenshots` folders and a card's `Download` folder are
watched already. Under **Folders watched** you can add others — a messaging
app's folder, or wherever a scanner app saves.

Each watched folder means a notification every time something lands in it, so
add the ones you actually file from. A folder added this way starts from the
moment you add it, so what is already in it is treated as old and is reachable
from *Already in your folders* rather than arriving as a hundred
notifications. Only folders on your own storage can be added: the watcher needs
a real path, and the safety check applies exactly as it does everywhere else.

## Asking about a backlog

Switching suggestions on with a full Downloads folder would otherwise mean a
request per file, each with its own wait. **Ask Claude about all N at once**
sends one request covering up to 25 waiting files, and the answers are held and
used as you file each one, so the waiting happens once.

Nothing extra is sent — the same metadata per file as a single ask. Answers are
matched back by the file's own name rather than by position, so a short or
reordered reply means a file has *no* suggestion rather than the *wrong* one.
If the batch fails, filing carries on exactly as before.

## What Magpie will not touch

The second half of the fail-safe. Every path, at both ends of every copy, goes
through one check (`core/Safety.kt`) before anything is read or written. It is
an **allowlist**: a path has to be inside a storage volume Android reports —
your internal storage, or a mounted card — or it is refused. A route nobody
thought of is refused by default rather than allowed by default.

Refused outright:

- **Anything that makes the phone work**: `/system`, `/vendor`, `/product`,
  `/apex`, `/odm`, `/proc`, `/sys`, `/dev`, `/boot`, `/data`, and the rest of
  the system partitions.
- **Other apps' private storage**: `Android/data`, `Android/obb`,
  `Android/media` on any volume.
- **Android's own bookkeeping**: `LOST.DIR`, `.android_secure`.
- **Paths that climb back out of themselves** with `..`.
- **Anything outside a known volume at all.**

When a copy is refused you are told so as a distinct outcome — the fail-safe
speaking, not a fault — with the reason. The rules are plain string logic so
they are unit tested without a device: see `SafetyTest.kt`, which covers the
system partitions, other apps' folders, `..` traversal, and the case where
`/storage/emulated/01` must not be mistaken for being inside
`/storage/emulated/0`.

## Notifications

One per file, never grouped. Each shows the filename, the size and the file
type, and clears itself after four minutes if it is ignored. **Dismissing a
notification never dismisses the file** — it stays in the waiting list. There is
also a quiet, permanent notice while watching is on, with a *Stop watching*
button.

## Naming, and why it comes first

**You name the file before you choose the folder**, and the name is what
decides the folder.

That order matters more than it sounds. A download's own filename often says
nothing useful — `Gen-3 Alpha 4471203.mp4` does not tell anyone which character
or which shot it is. No amount of cleverness can read a fact that is not in the
input. But the moment you type `ANNA_REF_03`, the fact is there, and a rule
matching `anna` can put the picker straight on `01_CHARACTER_MASTERS`.

So: tap the file, name it what it is, and the folder picker opens where that
kind of thing goes. The dialog tells you which rule your name has matched while
you are still typing, so the folder is never a surprise a step later.

The box is pre-filled with the original name and offers one or two tidied
variants: separators turned back into spaces, query strings and URL escapes
dropped, hash- and id-shaped runs removed, and capitalisation evened out. The
extension is always kept. Suggestions are built **only from the filename** —
never the date, the site it came from, or the folder you are filing into. If a
tidied name comes out identical to the original, it is not offered twice.

Naming is skipped for batch filing, where several files share one destination
and there is no single name to type.

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
- **Never run on a device or an emulator.** The filing engine and the SAF glue
  now run under Robolectric on every CI push, so they are executed rather than
  merely reasoned about — but on a JVM, not a phone. The watcher, the foreground
  service, the notifications and the whole UI are still reviewed and reasoned
  about, not observed working.

What that means in practice: the first thing to try is one small file, into a
folder on internal storage, and check the copy arrived before trusting it with
anything that matters. Then try one onto the SD card.

- **Filing is tested end to end, with real bytes on real disk.** The Storage
  Access Framework sits behind `DocumentStore`, so `Filing.kt` — every decision
  filing makes — runs against a store backed by a temporary directory.
  `FilingTest.kt` (20 cases) covers the duplicate path, the size and byte-count
  checks, a truncated write, a folder that will not list itself, one that
  refuses to create anything, one that renames what it is given, and one that
  will not report a size. Every one of those asserts the original is still on
  disk afterwards. Plus `SafetyTest.kt` (15 cases) on the path rules. All 35
  run on CI on every push.
- **The no-delete fail-safe is structural, not just tested.** `DocumentStore`
  has no delete method, so filing code cannot delete — there is nothing to
  call. `grep -rn "\.delete()\|deleteDocument" magpie/src/main` returns
  nothing. A test reflects over the interface and fails if anyone ever adds
  one.
- **The SAF glue is tested against a real `DocumentsProvider`**, run under
  Robolectric with the provider backed by a temporary directory
  (`SafDocumentStoreTest.kt`, 18 cases). Tree URIs, document ids, cursors and
  file descriptors all take their real code paths, so `SafDocumentStore.kt` is
  exercised as written rather than described. It covers a 700 KB write across
  buffer boundaries, an empty file, a provider that renames what it is given,
  one that hides sizes, one that refuses to create, and one that answers
  nothing — which must throw rather than look like an empty folder, because an
  empty folder means "no name is taken", which is the wrong thing to believe
  right before writing.
- **The Anthropic call is tested end to end against a local HTTP server**
  (`SuggesterTest.kt`, 21 cases), with the SDK pointed at it by a `baseUrl`
  argument the app itself never passes. Everything but Anthropic's own machines
  is covered: the request the SDK actually puts on the wire against the
  documented shape, a good reply, a reply that tries to escape the folder or
  become a dotfile or change the extension, a refusal, 401, 429, 400, 500, an
  unreachable server, a reply that is not JSON, an empty reply, and a blank key
  making no request at all. One test reads the request body and asserts the
  file's contents — and even its path on disk — are not in it.
- **No request has ever been made to Anthropic's actual servers from this
  code.** The call is exercised in full against a local server, so the request
  shape, the parsing and every failure path are known good — but no real key has
  ever been used and no reply has ever come back from Anthropic. What that
  leaves unproven is whether the live API answers exactly as the tests assume.

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
  the copy still works and is still verified.
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
- **Filing into the folder the file is already in** does nothing if the name is
  unchanged, and behaves as a rename if the name is different.
- If the destination provider does not report a size back, verification falls
  back to the byte count Magpie wrote, and the outcome says so.
- **Files on a card that has been taken out stay on the lists.** Magpie will not
  drop an entry unless it can read the folder it lived in and see that the file
  has gone, because guessing the other way would empty your lists every time a
  card was unplugged.
- **Already in your folders** lists the top level of each watched folder and of
  the memory card, newest first, up to sixty entries. It is a way to reach a
  file, not a file manager.

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

Unit tests cover the parts worth testing without a device: filing end to end
against a `DocumentStore` backed by a temporary directory (duplicates,
verification, and the no-delete guarantee), the path safety rules, filename
tidying and suggestion building, size and file-type wording, and the
finished-arriving rule.

### Testing

```
gradle :magpie:testDebugUnitTest
```

Seventy-four tests, all on the JVM, no device needed. The two that need
explaining:

- **`SafDocumentStoreTest`** runs under Robolectric against
  `TestDocumentsProvider`, a genuine `DocumentsProvider` backed by a temporary
  directory. That is what makes the Storage Access Framework testable at all.
- **`SuggesterTest`** stands up a local `HttpServer` and points the Anthropic
  SDK at it with the `baseUrl` argument on `Suggester.suggest`. Production never
  passes it, so the app always talks to Anthropic; the tests get to read what
  went on the wire.

### Checking the Anthropic call without a full build

The Android SDK cannot be installed in the environment this was written in, so
CI is the only thing that compiles the app — a four-minute round trip for a
typo. The one file that calls Anthropic does not need Android at all, though,
and can be compiled on its own against the real published jars:

```
kotlinc -cp anthropic-java-core.jar:anthropic-java-client-okhttp.jar:json.jar:kotlinx-coroutines-core-jvm.jar \
  core/Naming.kt core/Formatting.kt watch/SpottedFile.kt ai/FilingSuggestion.kt ai/Suggester.kt
```

`SpottedFile` refers to `Notifications.ONGOING_ID`, so it needs a two-line stub
for that object. `javap` on the same jars settles what a builder actually
accepts, which is faster and more reliable than remembering.

## Naming suggestions, and the one thing that leaves the phone

This is the only part of Magpie that touches the network, and it is off until
you switch it on.

**Turning it on.** On the main screen, in the *Ask Claude for a name* card:
flick the switch, paste an Anthropic API key, and — optionally — pick a
**library folder**. The
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
| `MANAGE_EXTERNAL_STORAGE` | Read the watched folders and the memory card. Scoped storage cannot read files Magpie did not create. Nothing is ever deleted with it. |
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
