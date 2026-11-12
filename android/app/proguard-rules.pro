# The one native entry point: R8 must keep its name, or the Rust symbol finds nothing.
-keep class dev.mdm.android.Native { *; }
# youtubedl-android reads its classes by reflection (JSON of yt-dlp's answers).
-keep class com.yausername.** { *; }
