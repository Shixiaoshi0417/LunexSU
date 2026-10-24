//! Manager records are capabilities, not a package-name allowlist.
use anyhow::{Result, ensure};
use serde::{Deserialize, Serialize};

pub struct Binding<'a> {
    pub package: &'a str,
    pub certificate_size: u32,
    pub certificate_sha256: &'a str,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Manager {
    pub schema_version: u32,
    #[serde(rename = "package")]
    pub package_name: String,
    pub uid: u32,
    pub previous_uid: i32,
    pub cert_sha256: String,
}

impl Binding<'_> {
    pub fn accepts(&self, uid: u32, size: u32, hash: &str) -> bool {
        matches!(
            self.package,
            "com.shixiaoshi0417.lunexsu" | "com.shixiaoshi0417.lunexsu.pr"
        ) && (10000..=19999).contains(&uid)
            && self.certificate_size > 1
            && size == self.certificate_size
            && self.certificate_sha256.len() == 64
            && self
                .certificate_sha256
                .bytes()
                .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
            && hash == self.certificate_sha256
    }

    pub fn record(
        &self,
        uid: u32,
        size: u32,
        hash: &str,
        previous: Option<&Manager>,
    ) -> Result<Manager> {
        ensure!(
            self.accepts(uid, size, hash),
            "Manager does not match the Core certificate binding"
        );
        let previous_uid = previous
            .filter(|old| {
                old.schema_version == 1
                    && old.package_name == self.package
                    && self.accepts(old.uid, size, &old.cert_sha256)
            })
            .map_or(-1, |old| {
                if old.uid != uid {
                    i32::try_from(old.uid).unwrap_or(-1)
                } else if (10000..=19999).contains(&old.previous_uid)
                    && i64::from(old.previous_uid) != i64::from(uid)
                {
                    // Repeated registration must not erase a revocation awaiting server refresh.
                    old.previous_uid
                } else {
                    -1
                }
            });
        Ok(Manager {
            schema_version: 1,
            package_name: self.package.to_owned(),
            uid,
            previous_uid,
            cert_sha256: hash.to_owned(),
        })
    }
}

pub fn package_uid(output: &str, package: &str) -> Result<u32> {
    ensure!(output.len() <= 65536, "Oversized package-manager response");
    let prefix = format!("package:{package} uid:");
    let mut matches = output
        .lines()
        .filter_map(|line| line.trim().strip_prefix(&prefix));
    let raw = matches
        .next()
        .ok_or_else(|| anyhow::anyhow!("Manager is not installed for user 0"))?;
    ensure!(
        matches.next().is_none() && raw.bytes().all(|b| b.is_ascii_digit()),
        "Ambiguous package UID"
    );
    let uid = raw.parse()?;
    ensure!(
        (10000..=19999).contains(&uid),
        "Only primary-user application UIDs can manage Sui"
    );
    Ok(uid)
}

pub fn base_apk(output: &str) -> Result<&str> {
    ensure!(output.len() <= 65536, "Oversized APK path response");
    let paths: Vec<_> = output
        .lines()
        .filter_map(|line| line.strip_prefix("package:"))
        .collect();
    ensure!(
        !paths.is_empty()
            && paths.iter().all(|p| p.starts_with("/data/app/")
                && std::path::Path::new(p).extension() == Some(std::ffi::OsStr::new("apk"))
                && !p.chars().any(char::is_control)
                && !p.split('/').any(|part| part == "..")),
        "Invalid installed APK path"
    );
    if paths.len() == 1 {
        return Ok(paths[0]);
    }
    let bases: Vec<_> = paths
        .into_iter()
        .filter(|p| p.ends_with("/base.apk"))
        .collect();
    ensure!(bases.len() == 1, "Ambiguous installed base APK");
    Ok(bases[0])
}
