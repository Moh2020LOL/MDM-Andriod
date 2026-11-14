# The one native entry point: R8 must keep its name, or the Rust symbol finds nothing.
-keep class dev.mdm.android.Native { *; }
# youtubedl-android reads its classes by reflection (JSON of yt-dlp's answers).
-keep class com.yausername.** { *; }

# If minifying is turned on again: these load classes by name too.
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.** { *; }
-dontwarn org.apache.commons.**
