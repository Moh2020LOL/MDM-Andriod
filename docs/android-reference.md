# Android app: reference for whoever works on it next (human or AI)

Read this first; it is the map. [docs/android.md](android.md) has the build steps and the
library's background; [android-handoff.md](android-handoff.md) says where the work stopped and
what to do next. **Update this file with every change** (the "Change log" at the end).

## What the app is

A phone download manager laid out like Free Download Manager's Android app (blue top bar with a
speed-mode chip, tabs All / Active / Completed, search, a row per download with a file-type icon,
a + button, dark mode). The engine is the desktop one (`crates/engine`, Rust) behind one JNI
door (`crates/android`); everything you see is Kotlin + Jetpack Compose in `android/`.

Not in the app: **torrents** (removed on purpose: the Rust side in commit `65f2bf2`, the Kotlin
side in the change below). To bring them back, look at `git show 65f2bf2^:crates/android` and
the `torrent_*` calls in the first version of `Downloads.kt`.

## Files (android/app/src/main/java/dev/mdm/android/)

| File | What it holds |
|---|---|
| `App.kt` | Process start: `Prefs.init`, then `Downloads.start` |
| `Prefs.kt` | Every setting as a `StateFlow` (speed mode, snail mode, floating window on/off, theme, first-run done). `SpeedMode` numbers live here |
| `Item.kt` | One list row (`Item`), its `Kind` (HTTP, VIDEO), `Status`, `FileType`, JSON save/load |
| `Downloads.kt` | The list, the queue (`schedule()`: at most `Prefs.maxSimultaneous()` run, oldest waiting first), the speed limit shared between running downloads (`applyLimits()`), polling the Rust library every 500 ms, saving `downloads.json`, exporting a finished file |
| `Native.kt` | The one JNI function: `Native.invoke(method, json)`. Methods are listed in `crates/android/src/lib.rs` |
| `VideoEngine.kt` | Video pages: yt-dlp downloads and merges itself, with a failure ladder ending in the Rust analyzer; `explain()` turns errors into plain sentences |
| `BubbleService.kt`, `ClipActivity.kt` | Optional floating button above every app; tap reads the clipboard link and adds it |
| `VideoDetector.kt` | Is this link a direct media file or a video page of a known site? One list of site patterns, used everywhere |
| `Overlay.kt` | The floating "Video found: Download" window over other apps (plain views, draggable, hides after 15 s) |
| `LinkWatcherService.kt` | OPTIONAL accessibility service: reads only the address bar of listed browsers and calls `Overlay.show` for video links. Address-bar ids are in `URL_BARS` |
| `BrowserScreen.kt` | Built-in browser (WebView): sniffs media requests, offers "Download video" for video pages, sends the page's file downloads to the list |
| `Permissions.kt` | What the app asks for, how to test it, which system screen grants it |
| `Files.kt` | Public Download/MDM export (MediaStore, Android 10+), open and share a finished file |
| `Screens.kt` | List screen, add dialog, speed dialog, settings screen, permissions screen |
| `Theme.kt` | FDM-like blue/slate colours, light and dark |
| `MainActivity.kt` | Routes between the screens, receives shared links |
| `DownloadService.kt` | Foreground service + notification that keeps downloads alive |

Resources: `res/values/strings.xml` and `res/values-ar/strings.xml` (**keep both in step**: same
keys, same `%1$s` placeholders), `res/xml/link_watcher.xml` (accessibility config: browsers
watched), `res/xml/file_paths.xml` (FileProvider), `res/drawable/ic_launcher_*.xml` and
`res/mipmap-anydpi-v26/` (adaptive icon: a white arrow over a tray on blue), `ic_stat_download.xml`
(notification icon).

## How the pieces talk

- UI reads `Downloads.items` (a `StateFlow<List<Item>>`); it never touches the engine. Actions go
  through `Downloads.add / pause / resume / remove / pauseAll / resumeAll / clearCompleted`.
- A new download is `QUEUED`; `schedule()` marks it `RUNNING` and calls `begin()` on an IO
  coroutine (a VIDEO first asks yt-dlp for the stream address, then the HTTP engine downloads it).
- `tick()` (every 500 ms) copies the engine's snapshot into the item; on `Done` the file is copied
  to the public Download/MDM folder (Android 10+), the private copy is deleted, `Item.uri` is set.
- Speed: `Prefs.totalLimitBps()` (mode limit, or the snail limit) is divided by the number of
  running downloads and sent as `limit` on start/resume and through `http_limit` when it changes.
- Video detection → overlay: `LinkWatcherService` (any listed browser) or the in-app browser →
  `VideoDetector.classify(url)` → `Overlay.show(...)` (only if `Prefs.overlayEnabled` and the
  overlay permission is granted) → tap → `Downloads.add(url, video = page)`.

## Builds

- All four processors: arm64-v8a, armeabi-v7a, x86, x86_64. `tools/android/build-rust.sh` builds
  the Rust library for the four (needs the `i686-linux-android` Rust target for x86).
- `android/app/build.gradle.kts` uses ABI splits: one APK per processor plus a universal one. Do
  not add `ndk.abiFilters` (AGP refuses it together with splits).
- `.github/workflows/android.yml` builds on GitHub, uploads the APKs and **fails if any is over
  300 MB**. Release APKs are signed with the debug key (installable on your own phone only).

## Known limits and things to check first on a real phone

1. **The Kotlin code was written without a compiler** (the build needs Google's Maven and the
   Android SDK, unreachable from the sandbox it was written in). The first CI run will likely show
   a few small compile errors: fix them, they are mechanical. Likeliest: Compose imports/overload
   names, `AndroidView(onRelease=…)`, the youtubedl-android coordinates in `libs.versions.toml`.
2. The Rust crate was only built for the host (`cargo check` and its 3 tests pass). The `x86`
   (i686) Android target has never been built: `ring`, `rquickjs-sys` or `network-interface` may
   need a `cfg` (see "Where to look if the first build fails" in [android.md](android.md)).
3. **Address-bar detection** depends on each browser's view id (`LinkWatcherService.URL_BARS`). A
   browser can rename it or show only the domain; test with Chrome and Firefox first. The
   in-app browser and the share sheet do not depend on it.
4. Android 15 limits a `dataSync` foreground service to about 6 hours a day.
5. Videos: one file with picture and sound (YouTube up to 720p); the stream address expires after
   some hours, a longer pause restarts from the start. yt-dlp's own request headers are not
   forwarded to the engine yet.
6. The speed numbers (Moderate 2 MB/s and 5 at once, Slow 250 KB/s and 2 at once, Snail 20 KB/s)
   are fixed; FDM lets the person edit them ("Change traffic limits"): not built.
7. Exporting to the public folder copies the file (briefly twice the space).

## Change log

- **2026-11-14**: quality list for video pages (`VideoEngine.listFormats`, `QualityDialog`, `Item.selector`); overlay and floating button open the app so the list shows. Not compiled.

- **2026-11-13 (second pass)**: ADM-style UI (drawer with categories, Downloading/Completed tabs, dark by default); `VideoEngine` replaces `VideoResolver` (yt-dlp downloads and merges, five-step failure ladder, explained errors, Details + Retry); Rust page analyzer `analyze` (`crates/android/src/analyze.rs`, tested on the host); optional floating button and deep detection; clipboard link offered on focus; MissingTranslation lint off. Kotlin not compiled.

- **2026-11-13**: FDM-style UI (tabs, search, speed chip, type icons, dark mode); queue and speed
  modes; settings; built-in browser with media sniffing; floating "video found" window (optional,
  with the optional accessibility address-bar watcher); permissions screen; public Download/MDM
  export, open and share; adaptive icon and notification icon; torrent removed from the Kotlin app
  (the Rust side was already); four processors, ABI splits, 300 MB check in CI; Arabic strings for
  everything new. Not compiled or run on a phone.
