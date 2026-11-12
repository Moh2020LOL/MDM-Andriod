//! HTTP, HTTPS, FTP and FTPS downloads: `mdm_engine::Download`, one per id the app holds.

use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{LazyLock, Mutex, MutexGuard};

use http::{HeaderMap, HeaderName, HeaderValue};
use mdm_engine::{Destination, Download, Options, Request, Snapshot};
use serde_json::{Value, json};
use url::Url;

use crate::{Reply, number, path_arg, rt, text};

static NEXT_ID: AtomicI64 = AtomicI64::new(1);
static DOWNLOADS: LazyLock<Mutex<HashMap<i64, Download>>> = LazyLock::new(Default::default);

fn table() -> MutexGuard<'static, HashMap<i64, Download>> {
    // A panic while the table was held leaves it as it was; the downloads are still there.
    DOWNLOADS.lock().unwrap_or_else(|e| e.into_inner())
}

fn id_arg(args: &Value) -> Result<i64, String> {
    args.get("id")
        .and_then(Value::as_i64)
        .ok_or_else(|| "missing \"id\"".to_owned())
}

/// The headers the app sends along (`{"Referer": "...", "Cookie": "..."}`).
fn headers(args: &Value) -> Result<HeaderMap, String> {
    let mut map = HeaderMap::new();
    if let Some(obj) = args.get("headers").and_then(Value::as_object) {
        for (k, v) in obj {
            let name = HeaderName::from_bytes(k.as_bytes()).map_err(|e| format!("{k}: {e}"))?;
            let value = HeaderValue::from_str(v.as_str().unwrap_or_default())
                .map_err(|e| format!("{k}: {e}"))?;
            map.insert(name, value);
        }
    }
    Ok(map)
}

/// The engine's options for a phone: the app's connection count and limit, the app's agent.
fn options(args: &Value) -> Options {
    let mut o = Options {
        max_connections: number(args, "connections").unwrap_or(8).clamp(1, 32) as u32,
        speed_limit: number(args, "limit").filter(|b| *b > 0),
        ..Options::default()
    };
    if let Some(ua) = crate::user_agent() {
        o.user_agent = ua;
        o.automatic_agent = false;
    }
    o
}

fn url_arg(args: &Value) -> Result<Url, String> {
    let raw = text(args, "url")?;
    let url = Url::parse(raw.trim()).map_err(|e| format!("not a link: {e}"))?;
    if mdm_engine::supported(&url) {
        Ok(url)
    } else {
        Err(format!("\"{}\" links are not supported here", url.scheme()))
    }
}

/// `probe {url, headers?}`: the size and file name, before downloading.
pub fn probe(args: &Value) -> Reply {
    let url = url_arg(args)?;
    let opts = options(args);
    let info = rt()
        .block_on(mdm_engine::probe(url, headers(args)?, &opts))
        .map_err(|e| e.to_string())?;
    Ok(json!({
        "final_url": info.final_url.as_str(),
        "size": info.size,
        "file_name": info.file_name,
    }))
}

/// `http_start {url, dir, name?, connections?, limit?, headers?}` → `{id}`: the file lands in
/// `dir` under `name`, else the name the server or the link suggests.
pub fn start(args: &Value) -> Reply {
    let dir = path_arg(args, "dir")?;
    // `name`: the app already knows the file's name (a video's title), which the link doesn't
    // say. Only its last part counts, and a taken name gets the next free number.
    let dest = match args
        .get("name")
        .and_then(Value::as_str)
        .and_then(|n| std::path::Path::new(n).file_name())
    {
        Some(name) => Destination::Free(dir.join(name)),
        None => Destination::Dir(dir),
    };
    let req = Request {
        url: url_arg(args)?,
        headers: headers(args)?,
        dest,
    };
    let opts = options(args);
    let _inside = rt().enter();
    let download = Download::start(req, opts);
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    table().insert(id, download);
    Ok(json!({ "id": id }))
}

/// `http_resume {state_file, connections?, headers?}` → `{id}`: continue from the state file a
/// snapshot named (`state_file`), after a pause, a restart of the app or a dropped connection.
pub fn resume(args: &Value) -> Reply {
    let state = path_arg(args, "state_file")?;
    let opts = options(args);
    let headers = headers(args)?;
    let _inside = rt().enter();
    let download = Download::resume(state, headers, opts).map_err(|e| e.to_string())?;
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    table().insert(id, download);
    Ok(json!({ "id": id }))
}

fn snapshot_json(s: &Snapshot) -> Value {
    json!({
        "state": format!("{:?}", s.state),
        "url": s.url.as_str(),
        "file": s.file.as_ref().map(|p| p.to_string_lossy().into_owned()),
        // What `http_resume` takes: kept beside the file while the download isn't finished.
        "state_file": s.file.as_deref().map(|p| mdm_engine::state::state_path(p)
            .to_string_lossy().into_owned()),
        "size": s.size,
        "downloaded": s.downloaded,
        "resumable": s.resumable,
        "speed": s.connections.iter().map(|c| c.speed).sum::<u64>(),
        "connections": s.connections.len(),
        "error": s.error,
    })
}

/// `http_snapshot {id}`: where the download is now (cheap: the app asks every half second).
pub fn snapshot(args: &Value) -> Reply {
    let id = id_arg(args)?;
    let table = table();
    let d = table.get(&id).ok_or("no such download")?;
    Ok(snapshot_json(&d.snapshot()))
}

/// `http_cancel {id}`: stop it; the part file and its state stay, for `http_resume`.
pub fn cancel(args: &Value) -> Reply {
    let id = id_arg(args)?;
    table().get(&id).ok_or("no such download")?.cancel();
    Ok(json!({ "ok": true }))
}

/// `http_limit {id, bps}`: change its speed limit while it runs (0: none).
pub fn limit(args: &Value) -> Reply {
    let id = id_arg(args)?;
    let bps = number(args, "bps").filter(|b| *b > 0);
    table()
        .get(&id)
        .ok_or("no such download")?
        .set_speed_limit(bps);
    Ok(json!({ "ok": true }))
}

/// `http_finish {id}` → `{path, size}` or `{error}`: after a snapshot said Done, Failed or
/// Cancelled. Forgets the download.
pub fn finish(args: &Value) -> Reply {
    let id = id_arg(args)?;
    let download = table().remove(&id).ok_or("no such download")?;
    let outcome = rt().block_on(download.wait()).map_err(|e| e.to_string())?;
    Ok(json!({
        "path": outcome.path.to_string_lossy(),
        "size": outcome.size,
    }))
}
