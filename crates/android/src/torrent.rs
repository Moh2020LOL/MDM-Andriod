//! BitTorrent and magnet links: one `mdm_torrent::Torrents` session for the app, torrents named
//! by their info hash.

use std::path::Path;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use mdm_torrent::{Config, Source, Torrents};
use serde_json::{Value, json};

use crate::{Reply, number, path_arg, rt, text};

static SESSION: Mutex<Option<Arc<Torrents>>> = Mutex::new(None);

fn slot() -> MutexGuard<'static, Option<Arc<Torrents>>> {
    SESSION.lock().unwrap_or_else(|e| e.into_inner())
}

fn session() -> Result<Arc<Torrents>, String> {
    slot()
        .clone()
        .ok_or_else(|| "the torrent session isn't started (torrent_start)".to_owned())
}

/// `torrent_start {state_dir, port?}`: start the session (once; again is a no-op). `state_dir`
/// keeps librqbit's resume data and the DHT's nodes: torrents from an earlier run are still
/// in the session afterwards.
pub fn start(args: &Value) -> Reply {
    if slot().is_some() {
        return Ok(json!({ "ok": true, "already": true }));
    }
    let mut cfg = Config::new(path_arg(args, "state_dir")?);
    // Local peer discovery is multicast, which a phone on mobile data (or a Wi-Fi in power
    // save) doesn't deliver anyway; UPnP and the DHT stay on.
    cfg.lsd = false;
    cfg.port = number(args, "port").and_then(|p| u16::try_from(p).ok());
    let session = rt()
        .block_on(Torrents::start(cfg))
        .map_err(|e| e.to_string())?;
    *slot() = Some(Arc::new(session));
    Ok(json!({ "ok": true }))
}

/// `torrent_add {source, dir, wait_secs?, paused?}` → `{hash, name, total, files}`.
///
/// `source` is a magnet link, or the path of a .torrent file the app saved. A magnet asks the
/// swarm for the file list first (at most `wait_secs`, default 90), so this call can take a
/// while: the app makes it off its main thread. Every file is downloaded, into a folder of the
/// torrent's name inside `dir`.
pub fn add(args: &Value) -> Reply {
    let t = session()?;
    let source = text(args, "source")?;
    let src = match Source::magnet(source) {
        Some(m) => m,
        None => Source::File(mdm_torrent::read_file(Path::new(source)).map_err(|e| e.to_string())?),
    };
    let wait = Duration::from_secs(number(args, "wait_secs").unwrap_or(90));
    let meta = rt()
        .block_on(t.metadata(&src, wait, None))
        .map_err(|e| e.to_string())?;
    let folder = meta.folder_in(&path_arg(args, "dir")?);
    let paused = args.get("paused").and_then(Value::as_bool).unwrap_or(false);
    let hash = rt()
        .block_on(t.add(&meta.torrent, &folder, None, meta.peers.clone(), paused))
        .map_err(|e| e.to_string())?;
    Ok(json!({
        "hash": hash,
        "name": meta.name,
        "total": meta.total(),
        "files": meta.files.iter().filter(|f| !f.padding).count(),
        "folder": folder.to_string_lossy(),
    }))
}

/// `torrent_stats {hash}`: the numbers as last read (every 200 ms inside the library); never
/// waits on the session.
pub fn stats(args: &Value) -> Reply {
    let t = session()?;
    let hash = text(args, "hash")?;
    let s = t.seen(hash).ok_or("this torrent is not in the session")?;
    Ok(json!({
        "phase": format!("{:?}", s.phase),
        "done": s.done,
        "total": s.total,
        "uploaded": s.uploaded,
        "down_speed": s.down_speed,
        "up_speed": s.up_speed,
        "peers_live": s.peers_live,
        "peers_seen": s.peers_seen,
        "error": s.error,
    }))
}

pub fn pause(args: &Value) -> Reply {
    let t = session()?;
    rt().block_on(t.pause(text(args, "hash")?))
        .map_err(|e| e.to_string())?;
    Ok(json!({ "ok": true }))
}

pub fn resume(args: &Value) -> Reply {
    let t = session()?;
    rt().block_on(t.resume(text(args, "hash")?))
        .map_err(|e| e.to_string())?;
    Ok(json!({ "ok": true }))
}

/// `torrent_remove {hash, delete_files?}`.
pub fn remove(args: &Value) -> Reply {
    let t = session()?;
    let delete = args
        .get("delete_files")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    rt().block_on(t.remove(text(args, "hash")?, delete))
        .map_err(|e| e.to_string())?;
    Ok(json!({ "ok": true }))
}

/// `torrent_stop`: close the session (the app is shutting down); resume data is written.
pub fn stop() -> Reply {
    if let Some(t) = slot().take() {
        rt().block_on(t.stop());
    }
    Ok(json!({ "ok": true }))
}
