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

    fun JSONObject.error(): String? = if (has("error")) optString("error") else null
}
