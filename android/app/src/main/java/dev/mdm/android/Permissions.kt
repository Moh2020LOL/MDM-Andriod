package dev.mdm.android

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Everything the app asks the phone for, why, whether it is granted, and the screen that grants
 * it. The onboarding screen (Screens.kt) lists these; Settings can open it again.
 */
object Permissions {
    enum class Kind { NOTIFICATIONS, OVERLAY, BATTERY, ACCESSIBILITY, STORAGE }

    fun canOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun ignoringBattery(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun storageGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun accessibilityOn(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val mine = ComponentName(context, LinkWatcherService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(mine, ignoreCase = true) }
    }

    /** Which permissions this phone's Android version has at all (Android 13+ notifications; 8-9 storage). */
    fun applicable(): List<Kind> = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Kind.NOTIFICATIONS)
        add(Kind.OVERLAY)
        add(Kind.BATTERY)
        add(Kind.ACCESSIBILITY)
        if (Build.VERSION.SDK_INT < 29) add(Kind.STORAGE)
    }

    fun granted(context: Context, kind: Kind): Boolean = when (kind) {
        Kind.NOTIFICATIONS -> notificationsGranted(context)
        Kind.OVERLAY -> canOverlay(context)
        Kind.BATTERY -> ignoringBattery(context)
        Kind.ACCESSIBILITY -> accessibilityOn(context)
        Kind.STORAGE -> storageGranted(context)
    }

    /** The system screen that grants an overlay, battery or accessibility permission. */
    fun settingsIntent(context: Context, kind: Kind): Intent? = when (kind) {
        Kind.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        Kind.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        Kind.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Kind.NOTIFICATIONS, Kind.STORAGE -> null // runtime dialogs, asked by the screen
    }

    fun runtimePermission(kind: Kind): String? = when (kind) {
        Kind.NOTIFICATIONS -> Manifest.permission.POST_NOTIFICATIONS
        Kind.STORAGE -> Manifest.permission.WRITE_EXTERNAL_STORAGE
        else -> null
    }
}
