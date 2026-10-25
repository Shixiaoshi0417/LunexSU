//! Validate the entire embedded payload before any runtime publication is allowed.
use anyhow::{Context, Result, ensure};
use serde::Deserialize;
use std::collections::{BTreeMap, BTreeSet};

const MAX_MANIFEST: usize = 64 * 1024;
const MAX_FILE: usize = 16 * 1024 * 1024;
const MAX_TOTAL: usize = 32 * 1024 * 1024;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Manifest {
    pub schema: u32,
    pub version: String,
    pub version_code: u32,
    pub primary_abi: String,
    pub secondary_abi: String,
    pub files: Vec<Entry>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Entry {
    pub name: String,
    pub mode: u32,
    pub sha256: String,
}

fn expected_files(secondary: &str) -> BTreeMap<String, u32> {
    let mut files = BTreeMap::new();
    for (name, mode) in [
        ("bin/sui", 0o755),
        ("bin/sepolicy_checker", 0o755),
        ("librish.so", 0o644),
        ("zygisk.so", 0o644),
        ("sui.dex", 0o600),
        ("sepolicy.rule", 0o644),
        ("rish", 0o770),
        ("licenses/LICENSE", 0o644),
        ("licenses/UPSTREAM.md", 0o644),
        ("licenses/api_LICENSE", 0o644),
        ("licenses/SOURCE.lock.json", 0o644),
        ("licenses/LUNEXSU.md", 0o644),
    ] {
        files.insert(name.to_owned(), mode);
    }
    files.insert(format!("zygisk.{secondary}.so"), 0o644);
    files
}

pub fn validate(read: impl Fn(&str) -> Result<Vec<u8>>, abi: &str) -> Result<Manifest> {
    let secondary = match abi {
        "arm64-v8a" => "armeabi-v7a",
        "x86_64" => "x86",
        _ => anyhow::bail!("Managed Sui is not supported on {abi}"),
    };
    let bytes = read("payload.json").context("Sui payload is not bundled")?;
    ensure!(bytes.len() <= MAX_MANIFEST, "Oversized Sui manifest");
    let manifest: Manifest = serde_json::from_slice(&bytes)?;
    ensure!(manifest.schema == 1, "Unsupported Sui manifest schema");
    ensure!(
        manifest.primary_abi == abi && manifest.secondary_abi == secondary,
        "Sui payload ABI does not match this daemon"
    );
    ensure!(
        !manifest.version.is_empty()
            && manifest.version.len() <= 128
            && manifest.version.bytes().all(|b| b.is_ascii_graphic())
            && (1..=i32::MAX as u32).contains(&manifest.version_code),
        "Invalid Sui version"
    );
    let expected = expected_files(secondary);
    ensure!(
        manifest.files.len() == expected.len(),
        "Incomplete Sui payload"
    );
    let mut names = BTreeSet::new();
    // Finish all metadata checks before passing any member name to a reader.
    for file in &manifest.files {
        ensure!(names.insert(&file.name), "Duplicate Sui member");
        ensure!(
            expected.get(&file.name) == Some(&file.mode),
            "Unexpected Sui path or mode: {}",
            file.name
        );
        ensure!(
            file.sha256.len() == 64
                && file
                    .sha256
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)),
            "Invalid Sui member digest"
        );
    }
    let mut total = 0;
    for file in &manifest.files {
        let data =
            read(&file.name).with_context(|| format!("Missing Sui member: {}", file.name))?;
        ensure!(
            !data.is_empty() && data.len() <= MAX_FILE,
            "Invalid Sui member size"
        );
        total += data.len();
        ensure!(total <= MAX_TOTAL, "Oversized Sui payload");
        ensure!(
            sha256::digest(&data) == file.sha256,
            "Sui member checksum mismatch: {}",
            file.name
        );
    }
    Ok(manifest)
}
