package dev.mdm.android

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * FDM-style speed modes. Numbers follow the Free Download Manager screen the app is styled after:
 * Fast = no limit, Moderate = about 2 MB/s, Slow = about 250 KB/s. [limitBps] 0 means no limit.
 */
enum class SpeedMode(val id: String, val maxSimultaneous: Int, val limitBps: Long) {
    FAST("fast", 10, 0L),
    MODERATE("moderate", 5, 2_000_000L),
    SLOW("slow", 2, 250_000L);

    companion object {
        fun fromId(id: String?): SpeedMode = entries.firstOrNull { it.id == id } ?: FAST
    }
}

/** Light, dark, or follow the phone. */
enum class ThemeChoice(val id: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun fromId(id: String?): ThemeChoice = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * Every setting the person can change, kept in SharedPreferences and exposed as flows so the
 * screens follow them. Named Prefs (not Settings) so it never clashes with android.provider.Settings.
 */
object Prefs {
    /** Snail mode: a tiny limit that frees the phone's bandwidth without stopping the downloads. */
    const val SNAIL_BPS = 20_000L

    private lateinit var prefs: SharedPreferences

    private val _speedMode = MutableStateFlow(SpeedMode.FAST)
    val speedMode: StateFlow<SpeedMode> = _speedMode

    private val _snail = MutableStateFlow(false)
    val snail: StateFlow<Boolean> = _snail

    /** The floating "video found" window over other apps. Off = never shown. */
    private val _overlayEnabled = MutableStateFlow(true)
    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled

    private val _theme = MutableStateFlow(ThemeChoice.SYSTEM)
    val theme: StateFlow<ThemeChoice> = _theme

    private val _onboardingDone = MutableStateFlow(false)
    val onboardingDone: StateFlow<Boolean> = _onboardingDone

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("mdm", Context.MODE_PRIVATE)
        _speedMode.value = SpeedMode.fromId(prefs.getString("speed_mode", null))
        _snail.value = prefs.getBoolean("snail", false)
        _overlayEnabled.value = prefs.getBoolean("overlay", true)
        _theme.value = ThemeChoice.fromId(prefs.getString("theme", null))
        _onboardingDone.value = prefs.getBoolean("onboarding_done", false)
    }

    fun setSpeedMode(m: SpeedMode) {
        _speedMode.value = m
        prefs.edit().putString("speed_mode", m.id).apply()
    }

    fun setSnail(on: Boolean) {
        _snail.value = on
        prefs.edit().putBoolean("snail", on).apply()
    }

    fun setOverlayEnabled(on: Boolean) {
        _overlayEnabled.value = on
        prefs.edit().putBoolean("overlay", on).apply()
    }

    fun setTheme(t: ThemeChoice) {
        _theme.value = t
        prefs.edit().putString("theme", t.id).apply()
    }

    fun setOnboardingDone(done: Boolean) {
        _onboardingDone.value = done
        prefs.edit().putBoolean("onboarding_done", done).apply()
    }

    /** The limit for ALL downloads together, in bytes per second (0: none). */
    fun totalLimitBps(): Long = if (_snail.value) SNAIL_BPS else _speedMode.value.limitBps

    fun maxSimultaneous(): Int = _speedMode.value.maxSimultaneous
}
