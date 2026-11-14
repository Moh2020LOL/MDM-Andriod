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

## State (2026-11-13, second pass: ADM-style update)

The owner's second request (informal Arabic, summarised): make it look like **ADM** (Advanced
Download Manager) instead of FDM; the app must analyse ANY link (also sites yt-dlp has no
extractor for) with a fast Rust analyser; an optional floating window/button above all apps; every
permission; files saved automatically; fix the old app's errors (`yt-dlp failed`, a cryptic "mf2");
and leave a file another AI can continue from (this one).

Done in the code, **Rust verified on the host, Kotlin NOT compiled, nothing run on a phone**:

- [x] **Rust page analyzer** `crates/android/src/analyze.rs`, JNI method `analyze {url, headers?}`:
      reads a page's source (og:video, `<video>`/`<source>`, JSON-LD, escaped URLs in scripts, one
      level of `<iframe>`), returns candidates best first (`file`, `hls`, `dash`, `page`). 5 unit
      tests pass (`cargo test --ignore-rust-version` in `crates/android`, 8 tests total).
- [x] **VideoEngine.kt** replaces VideoResolver.kt. yt-dlp now downloads AND merges (best video +
      best audio, ffmpeg in the APK) itself instead of "find one address, hand it to our engine"
      (that single-file request caused "Requested format is not available"). Failure ladder:
      default → update yt-dlp → other YouTube clients → single file → Rust analyzer (a file goes to
      the HTTP engine, an HLS/DASH/embedded player goes back to yt-dlp). `explain()` turns yt-dlp's
      text into a plain sentence (English / Arabic); the raw text is kept in `Item.detail` and shown
      under **Details** (copyable) with a **Retry** button.
- [x] ADM-style UI (`Screens.kt`, `Theme.kt`): side drawer with categories (videos, music,
      documents, apps, archives, images, other, with counts), tabs Downloading / Completed, dark by
      default, paste button and "analyze this page" / "sound only (MP3)" options in the add dialog.
- [x] Optional **floating button** above every app (`BubbleService.kt`, `ClipActivity.kt`; Settings →
      Floating button): tap = read the clipboard link, analyse, add. Off by default.
- [x] Optional **deep detection** (Settings): the accessibility watcher also analyses ANY page it
      sees in a browser's address bar and shows the "video found" window. Off by default.
- [x] A copied link is offered when the app comes to the front (`MainActivity.onWindowFocusChanged`);
      shared text like "Title https://…" is reduced to its link (`firstLink`).
- [x] Files are saved automatically: app folder while downloading, then exported to
      Download/MDM (MediaStore, no permission on Android 10+). Install-apk permission added.
- [x] **Quality list**: after "Download" on a video page, `QualityDialog` (Screens.kt) asks `VideoEngine.listFormats` (yt-dlp `-J`, same failure ladder) and shows one line per resolution with its size, plus sound only (MP3); the choice is stored as `Item.selector` (yt-dlp `-f`). If listing fails the dialog shows the reason and a "best available" button (its download still has the analyzer as last resort). The floating window and the floating button open the app with the link so the list shows.
- [x] Static checks: every `R.string` exists, English and Arabic keys equal, XML well-formed.

Not verified (say so honestly): the Gradle build, the Rust build for the four Android targets,
APK sizes, anything on a device, and **YouTube**: since late 2025 yt-dlp needs an external
JavaScript runtime for YouTube and youtubedl-android bundles only Python, so YouTube depends on the
"other clients" step working. If it fails, the next idea is to solve the challenge with the phone's
WebView (or to bundle a small JS runtime).

## Next (in this order)

1. **Build**: push, run the workflow (Actions → "Android APK") or `tools/android/build-rust.sh`
   then `gradle -p android assembleRelease`. Fix compile errors (likeliest: Compose imports,
   the youtubedl-android callback signature in `VideoEngine.ytdlp`, `addOption` for repeated flags).
2. On a phone: permissions screen, add a direct link, a YouTube link, a link of a small site with an
   embedded player (the analyzer), audio-only, pause/resume a video, a failed link → Details → Retry.
3. The owner's old errors: ask for the exact text of "mf2" (never found in this repository; the
   Details dialog now copies yt-dlp's raw output, which is what we need).
4. Then: editable speed limits, per-download details screen, Wi-Fi-only, scheduler, HLS download in
   the Rust engine (so streams get multi-connection speed), more languages, torrents.

## Rules of this repository that still apply (CLAUDE.md)

Clean room (no code, art, icons or text from other download managers: the icon here is drawn
from scratch; the FDM screenshots were used only as a layout reference), update `ROADMAP.md` with
every change, fix root causes, verify end to end. The desktop i18n rule (`crate::i18n`) is for the
desktop app; Android texts live in `res/values*/strings.xml`.

## How the owner works with this

Mohammed writes informal Arabic; reply in Arabic, short, conclusion first. He wants things done
rather than asked about, and the honest state of what is and is not tested.
