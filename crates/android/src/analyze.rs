//! The page analyzer: given ANY address, finds the media it holds, with no help from yt-dlp.
//!
//! Many sites yt-dlp has no extractor for (small video sites, a company's own player, a blog with
//! an embedded clip) still put the file's address in the page: an `og:video` tag, a `<video>` or
//! `<source>` element, JSON-LD, or a script that builds the player. This reads the page the way a
//! person would "view source", follows a few embedded players (`<iframe>`), and returns the
//! candidates best first. The app then downloads a direct file with the engine and a stream
//! (HLS `.m3u8`, DASH `.mpd`) or a site yt-dlp knows with yt-dlp/ffmpeg.
//!
//! `analyze {url, headers?}` → `{ final_url, title, direct, candidates: [{url, kind, audio,
//! quality, source}], headers }` where `kind` is `file`, `hls`, `dash` or `page` (an embedded
//! player on a site yt-dlp reads). Only reads: nothing is written, nothing is executed.

use std::collections::HashSet;
use std::sync::LazyLock;
use std::time::Duration;

use http::{HeaderMap, HeaderName, HeaderValue};
use mdm_engine::Options;
use regex::Regex;
use serde_json::{Value, json};
use url::Url;

use crate::{Reply, rt, text};

/// The most of a page that is read (media addresses sit in the first megabytes).
const MAX_PAGE: usize = 3 * 1024 * 1024;
/// Embedded players followed, one level deep.
const MAX_FRAMES: usize = 3;
const MAX_CANDIDATES: usize = 20;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Candidate {
    pub url: String,
    pub kind: &'static str,
    pub audio: bool,
    pub quality: Option<u32>,
    pub source: &'static str,
}

static MEDIA_URL: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r#"(?i)https?://[^\s"'<>\\)\]]+?\.(?:mp4|m4v|webm|mkv|mov|m3u8|mpd|mp3|m4a|aac|ogg|opus|flac|wav)(?:\?[^\s"'<>\\)\]]*)?"#,
    )
    .expect("regex")
});
static QUALITY: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)(?:[/_\-.=])(\d{3,4})p?(?:[/_\-.?&]|$)").expect("regex"));
static TAG: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?is)<(video|source|audio|iframe|meta|link)\b[^>]*>").expect("regex"));
static ATTR: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?is)([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))"#).expect("regex")
});
static TITLE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"(?is)<title[^>]*>(.*?)</title>").expect("regex"));
static JSON_LD_URL: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r#"(?i)"(?:contentUrl|embedUrl|contentURL)"\s*:\s*"([^"]+)""#).expect("regex")
});

/// Sites whose players yt-dlp reads: an `<iframe>` there is offered as a page.
const KNOWN_PLAYERS: &[&str] = &[
    "youtube.com", "youtube-nocookie.com", "youtu.be", "vimeo.com", "dailymotion.com",
    "streamable.com", "twitch.tv", "facebook.com", "rumble.com", "bilibili.com", "ok.ru",
    "vk.com", "tiktok.com", "instagram.com", "soundcloud.com",
];
/// Iframes that are never a video (ads, widgets, sign-in).
const SKIP_FRAMES: &[&str] = &[
    "doubleclick", "googlesyndication", "googletagmanager", "adservice", "facebook.com/plugins",
    "twitter.com/widgets", "platform.twitter", "recaptcha", "disqus", "addthis",
];

fn unescape(s: &str) -> String {
    s.replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\x26", "&")
        .replace("\\x2F", "/")
        .replace("&amp;", "&")
}

fn host_matches(url: &Url, list: &[&str]) -> bool {
    url.host_str().is_some_and(|h| {
        let h = h.to_ascii_lowercase();
        list.iter().any(|d| h == *d || h.ends_with(&format!(".{d}")))
    })
}

fn classify(url: &str) -> Option<(&'static str, bool)> {
    let path = url.split(['?', '#']).next().unwrap_or(url).to_ascii_lowercase();
    let ext = path.rsplit('.').next()?;
    Some(match ext {
        "m3u8" => ("hls", false),
        "mpd" => ("dash", false),
        "mp4" | "m4v" | "webm" | "mkv" | "mov" => ("file", false),
        "mp3" | "m4a" | "aac" | "ogg" | "opus" | "flac" | "wav" => ("file", true),
        _ => return None,
    })
}

fn junk(url: &str) -> bool {
    let l = url.to_ascii_lowercase();
    ["thumb", "poster", "preview", "sprite", "/ads/", "adserver", "tracking", "pixel", "sample.mp4"]
        .iter()
        .any(|w| l.contains(w))
}

fn attrs(tag: &str) -> Vec<(String, String)> {
    ATTR.captures_iter(tag)
        .map(|c| {
            let v = c.get(2).or(c.get(3)).or(c.get(4)).map_or("", |m| m.as_str());
            (c[1].to_ascii_lowercase(), v.to_owned())
        })
        .collect()
}

fn resolve(base: &Url, raw: &str) -> Option<Url> {
    let raw = unescape(raw.trim());
    if raw.is_empty() || raw.starts_with("data:") || raw.starts_with("blob:") || raw.starts_with("javascript:") {
        return None;
    }
    let u = base.join(&raw).ok()?;
    matches!(u.scheme(), "http" | "https").then_some(u)
}

fn push(found: &mut Vec<Candidate>, seen: &mut HashSet<String>, url: Url, source: &'static str, hint_audio: bool) {
    let s = url.to_string();
    if junk(&s) || !seen.insert(s.clone()) {
        return;
    }
    if let Some((kind, audio)) = classify(&s) {
        let quality = QUALITY.captures(&s).and_then(|c| c[1].parse().ok()).filter(|q| (144..=4320).contains(q));
        found.push(Candidate { url: s, kind, audio: audio || hint_audio, quality, source });
    }
}

/// What a page's source holds, best first, plus its iframes' addresses and its title.
pub fn extract(base: &Url, html: &str) -> (Vec<Candidate>, Vec<Url>, Option<String>) {
    let mut found: Vec<Candidate> = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    let mut frames: Vec<Url> = Vec::new();
    for m in TAG.find_iter(html) {
        let tag = m.as_str();
        let name = tag[1..].split(|c: char| !c.is_ascii_alphabetic()).next().unwrap_or("").to_ascii_lowercase();
        let a = attrs(tag);
        let get = |k: &str| a.iter().find(|(n, _)| n == k).map(|(_, v)| v.as_str());
        match name.as_str() {
            "video" | "source" | "audio" => {
                let src = get("src").or(get("data-src")).or(get("data-url"));
                if let Some(u) = src.and_then(|s| resolve(base, s)) {
                    // `<source>` without a media extension still has a type attribute.
                    let source = if name == "source" { "source-tag" } else { "video-tag" };
                    if classify(u.as_str()).is_some() {
                        push(&mut found, &mut seen, u, source, name == "audio");
                    } else if get("type").is_some_and(|t| t.starts_with("video/") || t.starts_with("audio/")) {
                        let s = u.to_string();
                        if seen.insert(s.clone()) {
                            found.push(Candidate { url: s, kind: "file", audio: get("type").is_some_and(|t| t.starts_with("audio/")), quality: None, source });
                        }
                    }
                }
            }
            "meta" => {
                let key = get("property").or(get("name")).unwrap_or("").to_ascii_lowercase();
                if matches!(
                    key.as_str(),
                    "og:video" | "og:video:url" | "og:video:secure_url" | "og:audio" | "og:audio:url"
                        | "twitter:player:stream" | "twitter:player:stream:url"
                ) && let Some(u) = get("content").and_then(|c| resolve(base, c))
                {
                    if classify(u.as_str()).is_some() {
                        push(&mut found, &mut seen, u, "og-meta", key.contains("audio"));
                    }
                }
            }
            "iframe" => {
                if let Some(u) = get("src").or(get("data-src")).and_then(|s| resolve(base, s)) {
                    let l = u.as_str().to_ascii_lowercase();
                    if !SKIP_FRAMES.iter().any(|w| l.contains(w)) && !frames.contains(&u) {
                        frames.push(u);
                    }
                }
            }
            _ => {}
        }
    }

    // JSON-LD / player configs: "contentUrl": "...".
    for c in JSON_LD_URL.captures_iter(html) {
        if let Some(u) = resolve(base, &c[1]) {
            push(&mut found, &mut seen, u, "json-ld", false);
        }
    }
    // Anything that looks like a media address in the page or its scripts (also JSON-escaped).
    let plain = unescape(html);
    for m in MEDIA_URL.find_iter(&plain) {
        if let Ok(u) = Url::parse(m.as_str().trim_end_matches([',', ';', '.'])) {
            push(&mut found, &mut seen, u, "script", false);
        }
    }

    // Best first: plain files, then HLS, then DASH; the stated source before the scraped one;
    // higher quality first.
    found.sort_by_key(|c| {
        let k = match c.kind { "file" => 0, "hls" => 1, _ => 2 };
        let s = u8::from(c.source == "script");
        (k, s, std::cmp::Reverse(c.quality.unwrap_or(0)))
    });
    found.truncate(MAX_CANDIDATES);

    // Frames of sites yt-dlp reads become "page" candidates.
    let mut kept = Vec::new();
    for f in frames {
        if host_matches(&f, KNOWN_PLAYERS) {
            let s = f.to_string();
            if seen.insert(s.clone()) {
                found.push(Candidate { url: s, kind: "page", audio: false, quality: None, source: "iframe" });
            }
        } else {
            kept.push(f);
        }
    }
    let title = TITLE.captures(html).map(|c| html_text(&c[1])).filter(|t| !t.is_empty());
    (found, kept, title)
}

fn html_text(s: &str) -> String {
    let t = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
    t.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn header_map(args: &Value) -> HeaderMap {
    let mut map = HeaderMap::new();
    if let Some(obj) = args.get("headers").and_then(Value::as_object) {
        for (k, v) in obj {
            if let (Ok(n), Ok(val)) = (HeaderName::from_bytes(k.as_bytes()), HeaderValue::from_str(v.as_str().unwrap_or(""))) {
                map.insert(n, val);
            }
        }
    }
    map
}

struct Page {
    final_url: Url,
    content_type: String,
    body: String,
}

async fn fetch_page(url: &Url, headers: HeaderMap, referer: Option<&str>) -> Result<Page, String> {
    let mut opts = Options::default();
    if let Some(ua) = crate::user_agent() {
        opts.user_agent = ua;
        opts.automatic_agent = false;
    }
    let client = mdm_engine::http_client(&opts, url).map_err(|e| e.to_string())?;
    let mut req = client
        .get(url.as_str())
        .headers(headers)
        .header("User-Agent", opts.user_agent.as_str())
        .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
        .timeout(Duration::from_secs(20));
    if let Some(r) = referer {
        req = req.header("Referer", r);
    }
    let mut resp = req.send().await.map_err(|e| e.to_string())?;
    let status = resp.status();
    if !status.is_success() {
        return Err(format!("the site answered {status}"));
    }
    let final_url = Url::parse(resp.url().as_str()).map_err(|e| e.to_string())?;
    let content_type = resp
        .headers()
        .get("content-type")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_ascii_lowercase();
    // A media file is never read: its type says enough.
    if content_type.starts_with("video/") || content_type.starts_with("audio/") {
        return Ok(Page { final_url, content_type, body: String::new() });
    }
    let mut bytes: Vec<u8> = Vec::new();
    while let Some(chunk) = resp.chunk().await.map_err(|e| e.to_string())? {
        bytes.extend_from_slice(&chunk);
        if bytes.len() >= MAX_PAGE {
            break;
        }
    }
    Ok(Page { final_url, content_type, body: String::from_utf8_lossy(&bytes).into_owned() })
}

fn to_json(c: &Candidate) -> Value {
    json!({ "url": c.url, "kind": c.kind, "audio": c.audio, "quality": c.quality, "source": c.source })
}

/// `analyze {url, headers?}`: see the module's text.
pub fn analyze(args: &Value) -> Reply {
    let raw = text(args, "url")?;
    let url = Url::parse(raw.trim()).map_err(|e| format!("not a link: {e}"))?;
    if !matches!(url.scheme(), "http" | "https") {
        return Err("only http and https pages can be analyzed".to_owned());
    }
    let headers = header_map(args);
    rt().block_on(async move {
        let page = fetch_page(&url, headers.clone(), None).await?;
        let ct = page.content_type.as_str();
        let ref_headers = json!({ "Referer": page.final_url.as_str() });
        // The address is itself a media file or a playlist.
        let direct_kind = if ct.starts_with("video/") || ct.starts_with("audio/") {
            Some(("file", ct.starts_with("audio/")))
        } else if ct.contains("mpegurl") {
            Some(("hls", false))
        } else if ct.contains("dash+xml") {
            Some(("dash", false))
        } else {
            None
        };
        if let Some((kind, audio)) = direct_kind {
            let c = Candidate { url: page.final_url.to_string(), kind, audio, quality: None, source: "direct" };
            return Ok(json!({ "final_url": c.url, "title": null, "direct": true, "candidates": [to_json(&c)], "headers": ref_headers }));
        }
        let (mut cands, frames, title) = extract(&page.final_url, &page.body);
        // One level of embedded players that are not a known site: their pages hold the file.
        for f in frames.into_iter().take(MAX_FRAMES) {
            if let Ok(p) = fetch_page(&f, headers.clone(), Some(page.final_url.as_str())).await {
                let (more, _, _) = extract(&p.final_url, &p.body);
                for mut c in more {
                    if c.kind != "page" && !cands.iter().any(|x| x.url == c.url) {
                        c.source = "iframe";
                        cands.push(c);
                    }
                }
            }
        }
        cands.truncate(MAX_CANDIDATES);
        Ok::<Value, String>(json!({
            "final_url": page.final_url.as_str(),
            "title": title,
            "direct": false,
            "candidates": cands.iter().map(to_json).collect::<Vec<_>>(),
            "headers": ref_headers,
        }))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn base() -> Url {
        Url::parse("https://example.com/watch/42").unwrap()
    }

    #[test]
    fn finds_og_video_and_relative_source_tags() {
        let html = r#"<html><head><title> My  clip </title>
            <meta property="og:video" content="https://cdn.example.com/v/clip_720.mp4?t=1&amp;s=2">
            </head><body><video poster="p.jpg"><source src="/media/low_360.mp4" type="video/mp4"></video></body>"#;
        let (c, _, title) = extract(&base(), html);
        assert_eq!(title.as_deref(), Some("My clip"));
        assert_eq!(c.len(), 2);
        assert_eq!(c[0].url, "https://cdn.example.com/v/clip_720.mp4?t=1&s=2");
        assert_eq!(c[0].quality, Some(720));
        assert_eq!(c[1].url, "https://example.com/media/low_360.mp4");
    }

    #[test]
    fn finds_escaped_urls_in_scripts_and_hls() {
        let html = r#"<script>var cfg={"hls":"https:\/\/s.example.net\/live\/master.m3u8?a=1&b=2","mp4":"https:\/\/s.example.net\/f\/video.mp4"};</script>"#;
        let (c, _, _) = extract(&base(), html);
        assert_eq!(c[0].kind, "file");
        assert_eq!(c[0].url, "https://s.example.net/f/video.mp4");
        assert_eq!(c[1].kind, "hls");
        assert_eq!(c[1].url, "https://s.example.net/live/master.m3u8?a=1&b=2");
    }

    #[test]
    fn iframes_known_sites_become_pages_others_are_followed() {
        let html = r#"<iframe src="https://www.youtube.com/embed/abc123"></iframe>
            <iframe src="//player.example.org/p/9"></iframe>
            <iframe src="https://googleads.g.doubleclick.net/x"></iframe>"#;
        let (c, frames, _) = extract(&base(), html);
        assert_eq!(c.len(), 1);
        assert_eq!(c[0].kind, "page");
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0].as_str(), "https://player.example.org/p/9");
    }

    #[test]
    fn json_ld_audio_and_junk() {
        let html = r#"<script type="application/ld+json">{"@type":"AudioObject","contentUrl":"https://a.example.com/pod/ep1.mp3"}</script>
            <img src="https://a.example.com/thumb_preview.mp4">"#;
        let (c, _, _) = extract(&base(), html);
        assert_eq!(c.len(), 1);
        assert!(c[0].audio);
        assert_eq!(c[0].source, "json-ld");
    }

    #[test]
    fn a_page_with_nothing_has_no_candidates_and_bad_input_is_an_error() {
        let (c, f, t) = extract(&base(), "<html><body>hello</body></html>");
        assert!(c.is_empty() && f.is_empty() && t.is_none());
        assert!(analyze(&json!({"url": "ftp://x/y"})).is_err());
    }
}
