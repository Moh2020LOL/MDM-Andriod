//! Mister Download Manager's Android library.
//!
//! The Kotlin app (`android/`) holds the screens, the notifications and the foreground service;
//! this library holds what downloads: `mdm-engine` (HTTP, HTTPS, FTP, FTPS: segments, resume,
//! limits) and `mdm-torrent` (BitTorrent and magnet links, librqbit). They reach each other
//! through ONE native function, `Native.call(method, argsJson): String`, with JSON on both sides:
//! one place where the two languages meet, nothing to keep in step but the method names below.
//!
//! Every answer is a JSON object; a failure is `{"error": "<text>"}`, never an exception (a
//! panic is caught at the door and becomes one too).
//!
//! This is the crate's only unsafe code: the exported JNI symbol (`#[unsafe(no_mangle)]`). The
//! desktop workspace's `unsafe_code = "deny"` doesn't reach it (it has a workspace of its own).
#![allow(unsafe_code)]

mod http_dl;

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::PathBuf;
use std::sync::OnceLock;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use serde_json::{Value, json};
use tokio::runtime::Runtime;

/// What every method returns: the answer, or the error's text.
pub(crate) type Reply = Result<Value, String>;

/// The runtime every download and the torrent session run on (the engine needs one around it).
pub(crate) fn rt() -> &'static Runtime {
    static RT: OnceLock<Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .thread_name("mdm-worker")
            .enable_all()
            .build()
            .expect("a Tokio runtime starts")
    })
}

/// The User-Agent the app chose (`init`): the phone's Chrome, as the app knows it.
static USER_AGENT: OnceLock<String> = OnceLock::new();

pub(crate) fn user_agent() -> Option<String> {
    USER_AGENT.get().cloned()
}

/// A required text argument.
pub(crate) fn text<'a>(args: &'a Value, key: &str) -> Result<&'a str, String> {
    args.get(key)
        .and_then(Value::as_str)
        .ok_or_else(|| format!("missing \"{key}\""))
}

/// An optional number argument.
pub(crate) fn number(args: &Value, key: &str) -> Option<u64> {
    args.get(key).and_then(Value::as_u64)
}

/// `init`: the app's folders and agent; call once, before anything else.
fn init(args: &Value) -> Reply {
    if let Some(ua) = args.get("user_agent").and_then(Value::as_str) {
        let _ = USER_AGENT.set(ua.to_owned());
    }
    // Starts the runtime now rather than at the first download.
    rt();
    Ok(json!({ "ok": true, "version": env!("CARGO_PKG_VERSION") }))
}

fn dispatch(method: &str, args: &Value) -> Reply {
    match method {
        "init" => init(args),
        "probe" => http_dl::probe(args),
        "http_start" => http_dl::start(args),
        "http_resume" => http_dl::resume(args),
        "http_snapshot" => http_dl::snapshot(args),
        "http_cancel" => http_dl::cancel(args),
        "http_limit" => http_dl::limit(args),
        "http_finish" => http_dl::finish(args),
        other => Err(format!("unknown method \"{other}\"")),
    }
}

/// `dispatch`, as the JSON text the app reads; a panic becomes an error, not a crash.
fn call(method: &str, args_json: &str) -> String {
    let answer = catch_unwind(AssertUnwindSafe(|| {
        let args: Value = if args_json.trim().is_empty() {
            Value::Null
        } else {
            serde_json::from_str(args_json).map_err(|e| format!("bad arguments: {e}"))?
        };
        dispatch(method, &args)
    }))
    .unwrap_or_else(|_| Err("the library panicked".to_owned()));
    match answer {
        Ok(v) => v,
        Err(e) => json!({ "error": e }),
    }
    .to_string()
}

/// `dev.mdm.android.Native.call(method: String, argsJson: String): String`
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mdm_android_Native_call<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    method: JString<'local>,
    args: JString<'local>,
) -> jstring {
    let read = |env: &mut JNIEnv<'local>, s: &JString<'local>| -> String {
        env.get_string(s).map(String::from).unwrap_or_default()
    };
    let method = read(&mut env, &method);
    let args = read(&mut env, &args);
    let out = call(&method, &args);
    env.new_string(out)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// A folder argument as a path.
pub(crate) fn path_arg(args: &Value, key: &str) -> Result<PathBuf, String> {
    text(args, key).map(PathBuf::from)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unknown_methods_are_errors_not_panics() {
        let out: Value = serde_json::from_str(&call("nope", "{}")).unwrap();
        assert!(out["error"].as_str().unwrap().contains("nope"));
    }

    #[test]
    fn bad_json_is_an_error() {
        let out: Value = serde_json::from_str(&call("probe", "{")).unwrap();
        assert!(out["error"].as_str().unwrap().contains("bad arguments"));
    }

    #[test]
    fn a_missing_argument_is_named() {
        let out: Value = serde_json::from_str(&call("probe", "{}")).unwrap();
        assert_eq!(out["error"], "missing \"url\"");
    }
}
