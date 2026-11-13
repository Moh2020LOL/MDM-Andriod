# Android

MDM for Android is two pieces:

| Piece | Where | What it is |
|---|---|---|
| The library | `crates/android` | `mdm-engine` (HTTP, HTTPS, FTP, FTPS) and `mdm-torrent` (BitTorrent, magnet links) behind **one** JNI function, `Native.call(method, argsJson)`, JSON in and JSON out |
| The app | `android/` | Kotlin and Jetpack Compose: the list, the add dialog, the foreground service and its notification, links from the share sheet |

The desktop app's window (`crates/app`, egui) is not used on the phone: it is drawn for a mouse, many
windows and Win32. The engine never knew about it, so it moves over as it is.

> **Where things are.** The map of the code and the change log are in [android-reference.md](android-reference.md); where the work stopped is in [android-handoff.md](android-handoff.md). Torrents were removed (see the reference); the torrent rows below describe the first version.
>
> **Status.** Written, not yet built or run on a phone. The first build will show small
> mismatches (a version, a name); the list at the end says where to look first.

## Build

1. Install Android Studio (its SDK Manager: SDK Platform 35, SDK Tools → NDK) and Rust (rustup).
2. `export ANDROID_NDK_HOME=<sdk>/ndk/<version>`
3. `tools/android/build-rust.sh`: builds `libmdm_android.so` for arm64-v8a, armeabi-v7a, x86 and
   x86_64 into `android/app/src/main/jniLibs/`.
4. Open `android/` in Android Studio (it makes the Gradle wrapper), run the `app` on a phone.

## What changed in the libraries

| Library | On the desktop | On Android | Why |
|---|---|---|---|
| `rustls-platform-verifier` (HTTPS, FTPS, DNS over TLS/HTTPS) | the system's certificate store | Mozilla's roots, bundled (`webpki-root-certs`) | the platform verifier needs a Java component set up from the app; a bare `.so` has none. A certificate you installed in Android's settings is not trusted |
| `wreq` + BoringSSL (the browser-like connection, #668) | on | **off** (`default-features = false`) | builds BoringSSL from C++ sources; a site whose bot check refuses MDM stays refused |
| `yt-dlp` as a separate program (`mdm-tools`) | run as a child process | youtubedl-android (Python, yt-dlp and ffmpeg inside the APK) | a phone app can't run a downloaded program |
| `eframe` / `egui` window, tray, `win32.rs`, `unix.rs`, `mdm-ipc`, `mdm-nmhost`, `mdm-remote` | the app | not built | desktop things: windows, tray, browser host, local socket |
| Browser extension | catches downloads | the share sheet and "open with" (`AndroidManifest.xml`) | phones' browsers don't load MV3 extensions that way |
| `dirs` / data folder | `%APPDATA%`, `~/.local/share` | the app passes its folders in (`torrent_start`, `http_start`) | Android has none of those |

Engine code changed (desktop behaviour is the same): `crates/engine/src/tls.rs` and `ftp.rs`
(`cfg(target_os = "android")`), `crates/engine/Cargo.toml` (`webpki-root-certs` is no longer
optional: it is a small data crate). The root `Cargo.toml` excludes `crates/android`, which has a
workspace of its own, so the desktop build and its `Cargo.lock` don't change.

## What it does today

HTTP, HTTPS, FTP, FTPS downloads with many connections, pause and resume; torrents and magnet
links (a `.torrent` file from the picker or "open with"); video pages through yt-dlp, handed to the
engine so they keep the multi-connection speed; a foreground service, so downloads go on with the
screen off; Arabic and English (RTL works through Compose).

## Known limits

- Files are saved in the app's own Downloads folder (`Android/data/dev.mdm.android/files/Download`):
  no storage permission. Moving finished files to the public Downloads folder (MediaStore) is next.
- Videos: one file with picture and sound together (YouTube: up to 720p). Higher qualities need
  ffmpeg to join two streams. A stream address expires after some hours, so a video paused for
  that long restarts from the start.
- Android 15 limits a `dataSync` foreground service to about 6 hours per day; for very long
  torrents a different service type or WorkManager is needed.
- Not on the phone yet: queues and the scheduler, the speed limit control (the library has
  `http_limit`), per-file torrent choice (the library downloads every file), debrid accounts,
  themes, the other 84 languages (`i18n/*.tsv` would become Android string resources).

## Where to look if the first build fails

- **Cargo can't find a crate or feature**: `crates/android` has its own `Cargo.lock`; the engine's
  versions come from the root `Cargo.toml`. If `rquickjs-sys` has no bindings for an Android
  target, build with its `bindgen` feature or turn off the proxy-script module on Android.
- **`network-interface` on Android**: if it doesn't build, `net::adapters()` needs an Android
  version of the list (an empty list is acceptable: no adapter choice on the phone).
- **`UnsatisfiedLinkError`**: the `.so` is missing for the phone's processor, or the class
  `dev.mdm.android.Native` was renamed (the Rust symbol is `Java_dev_mdm_android_Native_call`).
- **Gradle versions** (`android/gradle/libs.versions.toml`): they exist, but are from late 2024;
  Android Studio will offer newer ones. The youtubedl-android coordinates are the ones to check
  first.
