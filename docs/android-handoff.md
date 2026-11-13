# Android: handoff (where the work stopped, how to continue)

For an AI (or a person) picking this up. Start with [android-reference.md](android-reference.md)
(the map of the code). This file is the **state**; rewrite its "State" and "Next" sections when
you stop. If your budget is nearly spent, stop at a clean point, update this file, and say so.

## The request this work answers (from the owner, Mohammed)

An Android app styled like **Free Download Manager** for phones, with all its features except
torrents (those wait: the torrent client did not work yet). Required:

1. A floating window over the screen the moment a downloadable video is recognised; the person
   can switch it off.
2. Ask for every permission the app needs to work at its best.
3. As small as it can be, never over 300 MB.
4. Keep a reference file of every change, so an AI can get up to speed fast (that is
   `android-reference.md`: keep its change log current).
5. An icon.
6. Run on arm64, 32-bit ARM, x86 and x86_64.
7. If the budget runs out: stop and leave a file that lets another AI continue (this file).

Any library or language may be added if it helps.

## State (2026-11-13)

Done in the code, **none of it compiled or run on a phone**:

- [x] Torrent removed from the Kotlin app
- [x] FDM-style UI: top bar with the speed-mode chip, tabs, search, rows, dark mode (`Screens.kt`, `Theme.kt`)
- [x] Queue, Fast / Moderate / Slow modes, Snail mode, settings (`Downloads.kt`, `Prefs.kt`)
- [x] Floating "video found" window (`Overlay.kt`), optional address-bar watcher
      (`LinkWatcherService.kt`), built-in browser with media sniffing (`BrowserScreen.kt`),
      the on/off switch in Settings
- [x] Permissions screen: notifications, display over other apps, battery, accessibility (optional), storage (Android 8-9)
- [x] Public Download/MDM export, open, share (`Files.kt`)
- [x] Icon (own vector design, adaptive) and notification icon
- [x] Four processors (`build-rust.sh`, `build.gradle.kts` splits, workflow), 300 MB check in CI
- [x] Rust crate: `cargo check` and `cargo test` pass on the host (3 tests)
- [x] Static checks: every `R.string` / drawable / xml reference exists, English and Arabic strings
      have the same keys and placeholders, all XML is well-formed, brackets balance in every .kt file

Not verified (be honest about this when reporting): the Gradle build, the Rust build for the four
Android targets (especially `i686-linux-android`), the APK sizes, anything on a device.

## Next (in this order)

1. **Run the workflow** (Actions → "Android APK" → Run workflow) or build locally
   (`tools/android/build-rust.sh`, then `gradle -p android assembleRelease`). Fix compile errors
   until it is green. Check the printed APK sizes against 300 MB (the universal APK is the one at risk;
   if it is over, drop `isUniversalApk` and publish the four split APKs).
2. On a phone: first start (permissions screen), add a link, pause/resume, a finished file appears
   in Download/MDM and opens, Arabic layout (right to left), dark mode.
3. Test the video flow: a YouTube link shared to the app; the in-app browser on a page with a
   video; then with the accessibility service on, Chrome and Firefox: does the floating window
   appear? If a browser shows only the domain in its address bar, `URL_BARS` or the approach needs
   changing for that browser.
4. Then, by value: editable speed limits ("Change traffic limits" in FDM), forwarding yt-dlp's
   request headers to the engine, a details screen per download, Wi-Fi-only option, scheduler,
   more languages (`i18n/*.tsv` → Android string resources), and torrents again.

## Rules of this repository that still apply (CLAUDE.md)

Clean room (no code, art, icons or text from other download managers: the icon here is drawn
from scratch; the FDM screenshots were used only as a layout reference), update `ROADMAP.md` with
every change, fix root causes, verify end to end. The desktop i18n rule (`crate::i18n`) is for the
desktop app; Android texts live in `res/values*/strings.xml`.

## How the owner works with this

Mohammed writes informal Arabic; reply in Arabic, short, conclusion first. He wants things done
rather than asked about, and the honest state of what is and is not tested.
