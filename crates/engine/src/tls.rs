//! The certificates secure connections (HTTPS, FTPS, DNS over TLS or HTTPS) check servers with:
//! the system's. A system without any (a minimal Linux without the `ca-certificates` package,
//! #264) still gives every client: plain HTTP and FTP work, and each secure connection fails
//! with [`NO_ROOTS`], which the engine reports as `Error::NoCertificates`. The torrent session's
//! HTTP clients take the same setup (mdm-torrent), so a session still starts there: DHT, peers
//! and plain HTTP trackers work, HTTPS trackers fail with [`NO_ROOTS`].

use std::sync::Arc;
#[cfg(not(target_os = "android"))]
use std::sync::atomic::{AtomicBool, Ordering};

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::CryptoProvider;
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};

/// Why a secure connection failed on a system without certificates: the error its handshake
/// gives, in English (the command line and the event log); the interfaces word it themselves.
pub const NO_ROOTS: &str = "this system has no trusted certificates to check the server with \
                            (install the ca-certificates package)";

/// The crypto provider MDM's own TLS configurations use (the one reqwest uses too).
pub(crate) fn provider() -> Arc<CryptoProvider> {
    Arc::new(rustls::crypto::aws_lc_rs::default_provider())
}

/// Whether the system has certificates to check servers with. Windows and macOS always do
/// (their verifiers ask the system at each connection); Linux reads its CA bundle, which can be
/// missing. Asked again until it's found, so installing the certificates while MDM runs is seen
/// by the next download.
pub fn system_has_roots() -> bool {
    // Android: Mozilla's roots travel with the program (`bundled_roots_config`), so there are
    // always some. The platform verifier would need the app's Java side set up first.
    #[cfg(target_os = "android")]
    {
        true
    }
    #[cfg(not(target_os = "android"))]
    {
        static FOUND: AtomicBool = AtomicBool::new(false);
        if FOUND.load(Ordering::Relaxed) {
            return true;
        }
        let found = rustls_platform_verifier::Verifier::new(provider()).is_ok();
        if found {
            FOUND.store(true, Ordering::Relaxed);
        }
        found
    }
}

/// Android's client configuration (HTTPS, FTPS, DNS over TLS or HTTPS): Mozilla's roots
/// (`webpki-root-certs`) plus `extra` (the tests' own CA). Certificates the person installed in
/// Android's settings (a company's, an inspecting proxy's) are not trusted: reading that store
/// takes the platform verifier's Java component, which a bare Rust library can't bring.
#[cfg(target_os = "android")]
pub(crate) fn bundled_roots_config(
    extra: &[Vec<u8>],
) -> Result<rustls::ClientConfig, rustls::Error> {
    let mut roots = rustls::RootCertStore::empty();
    roots.add_parsable_certificates(webpki_root_certs::TLS_SERVER_ROOT_CERTS.iter().cloned());
    for der in extra {
        roots.add(CertificateDer::from(der.clone()))?;
    }
    Ok(rustls::ClientConfig::builder_with_provider(provider())
        .with_safe_default_protocol_versions()?
        .with_root_certificates(roots)
        .with_no_client_auth())
}

/// `builder` with the TLS it can have here: reqwest's own (the system's verifier) when the
/// system has certificates, else one whose every secure connection fails with [`NO_ROOTS`]
/// (reqwest's would refuse to build the client at all, plain HTTP included). MDM's own clients
/// get it through `net::bind_client`; the torrent session's (trackers, UPnP) through librqbit's
/// `SessionOptions::configure_http_client` (mdm-torrent).
pub fn configure(builder: reqwest::ClientBuilder) -> reqwest::ClientBuilder {
    configure_with(builder, system_has_roots())
}

fn configure_with(builder: reqwest::ClientBuilder, has_roots: bool) -> reqwest::ClientBuilder {
    #[cfg(target_os = "android")]
    if has_roots {
        return builder.tls_backend_preconfigured(
            bundled_roots_config(&[]).expect("the default protocol versions are supported"),
        );
    }
    if has_roots {
        builder
    } else {
        builder.tls_backend_preconfigured(no_roots_config())
    }
}

/// A client configuration for a system without certificates: every server is refused.
pub(crate) fn no_roots_config() -> rustls::ClientConfig {
    let provider = provider();
    let verifier = Arc::new(NoRoots(provider.clone()));
    rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .expect("the default protocol versions are supported")
        .dangerous()
        .with_custom_certificate_verifier(verifier)
        .with_no_client_auth()
}

/// Whether `e` (or one of its causes) is a secure connection refused for want of certificates.
pub fn is_no_roots(e: &(dyn std::error::Error + 'static)) -> bool {
    let mut next = Some(e);
    while let Some(e) = next {
        if e.to_string().contains(NO_ROOTS) {
            return true;
        }
        next = e.source();
    }
    false
}

/// The verifier of a system without certificates: no server can be checked, so none is trusted.
#[derive(Debug)]
struct NoRoots(Arc<CryptoProvider>);

fn refused() -> rustls::Error {
    rustls::Error::General(NO_ROOTS.to_owned())
}

impl ServerCertVerifier for NoRoots {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Err(refused())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Err(refused())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Err(refused())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.0.signature_verification_algorithms.supported_schemes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mdm_labserver::{Config, LabServer, ftp};

    /// A client of a system without certificates.
    fn without_roots() -> reqwest::Client {
        configure_with(reqwest::Client::builder(), false)
            .build()
            .expect("a client builds without certificates")
    }

    #[tokio::test]
    async fn without_certificates_plain_http_still_downloads() {
        let lab = LabServer::start(Config::default()).await.unwrap();
        let body = without_roots()
            .get(lab.url("/gen/16K?seed=3"))
            .send()
            .await
            .unwrap()
            .bytes()
            .await
            .unwrap();
        assert_eq!(body.len(), 16 * 1024);
    }

    #[tokio::test]
    async fn without_certificates_https_says_why() {
        // An implicit-FTPS server opens with a TLS handshake, as an HTTPS server does: the
        // client sees its certificate and refuses it before any HTTP.
        let server = ftp::FtpServer::start(
            "127.0.0.1:0".parse().unwrap(),
            ftp::FtpConfig {
                tls: ftp::FtpTls::Implicit,
                ..Default::default()
            },
        )
        .await
        .unwrap();
        let e = without_roots()
            .get(format!("https://{}/", server.addr()))
            .send()
            .await
            .unwrap_err();
        assert!(is_no_roots(&e), "{e:?}");
        let e = crate::Error::from(e);
        assert!(matches!(e, crate::Error::NoCertificates), "{e:?}");
        assert_eq!(e.to_string(), NO_ROOTS);
    }

    #[test]
    fn other_tls_failures_are_not_mistaken_for_it() {
        let e = std::io::Error::other(rustls::Error::InvalidCertificate(
            rustls::CertificateError::UnknownIssuer,
        ));
        assert!(!is_no_roots(&e));
        assert!(is_no_roots(&std::io::Error::other(refused())));
    }
}
