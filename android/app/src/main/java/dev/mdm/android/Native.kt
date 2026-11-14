package dev.mdm.android

import org.json.JSONObject

/**
 * The Rust library (crates/android): ONE native function, JSON in and JSON out. The method names
 * and their arguments are listed in crates/android/src/lib.rs.
 */
object Native {
    init {
        System.loadLibrary("mdm_android")
    }

    // The symbol Java_dev_mdm_android_Native_call: this class's name and package are part of it.
    @JvmStatic
    private external fun call(method: String, argsJson: String): String

    /** Calls [method]; a failure comes back as `{"error": "..."}` (see [error]). Blocks: never on the main thread. */
    fun invoke(method: String, args: JSONObject = JSONObject()): JSONObject =
        try {
            JSONObject(call(method, args.toString()))
        } catch (e: Throwable) {
            JSONObject().put("error", e.message ?: e.javaClass.simpleName)
        }

    /**
     * The failure text, or null when there is none. The library puts `"error": null` into every
     * snapshot: a key that is present is NOT an error, and org.json's optString turns a JSON null
     * into the text "null" (that was every download failing with "null").
     */
    fun JSONObject.error(): String? = if (has("error") && !isNull("error")) optString("error").ifEmpty { null } else null

    /** A text field that may be a JSON null: "" then (never the word "null"). */
    fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)
}
