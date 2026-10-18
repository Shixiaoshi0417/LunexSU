//! Prepare a disabled module using the same journal as ordinary module installs.
//! Starting servers, policy changes and changing the desired enabled state are separate gates.
use super::payload;
use crate::module::transaction::Store;
use anyhow::{Context, Result, ensure};
use std::{
    cell::RefCell,
    collections::BTreeMap,
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::Path,
};

pub const MODULE_ID: &str = "lunexsu-sui";
const OWNER: &str = ".lunexsu-managed-sui";
const OWNER_CONTENT: &[u8] = b"lunexsu-managed-sui:1\n";
const CONFLICTS: [&str; 5] = ["zygisk-sui", "zygisk_sui", "riru_sui", "sui", "finasu-sui"];

pub struct Paths<'a> {
    pub transactions: &'a Path,
    pub updates: &'a Path,
    pub modules: &'a Path,
}

/// Recover an interrupted activation before any module policy or Zygisk hooks are loaded.
pub fn disable_owned(paths: &Paths<'_>) -> Result<()> {
    let _store = Store::open(paths.transactions, paths.updates, paths.modules)?;
    for parent in [paths.modules, paths.updates] {
        let path = parent.join(MODULE_ID);
        if owned(&path)? {
            write_file(&path.join("disable"), b"", 0o644)?;
            File::open(path)?.sync_all()?;
        }
    }
    Ok(())
}

pub(super) fn exists(path: &Path) -> Result<bool> {
    match fs::symlink_metadata(path) {
        Ok(_) => Ok(true),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(error) => Err(error.into()),
    }
}

pub fn owned(path: &Path) -> Result<bool> {
    if !exists(path)? {
        return Ok(false);
    }
    ensure!(
        fs::symlink_metadata(path)?.is_dir(),
        "Sui path is not a real directory"
    );
    let marker = path.join(OWNER);
    if !exists(&marker)? {
        return Ok(false);
    }
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(marker)?;
    ensure!(file.metadata()?.is_file(), "Invalid Sui ownership marker");
    let mut bytes = Vec::new();
    file.take(128).read_to_end(&mut bytes)?;
    Ok(bytes == OWNER_CONTENT)
}

pub(super) fn check_conflicts(paths: &Paths<'_>) -> Result<()> {
    for parent in [paths.modules, paths.updates] {
        for id in CONFLICTS {
            ensure!(
                !exists(&parent.join(id))?,
                "External Sui installation conflicts: {id}"
            );
        }
    }
    Ok(())
}

fn preflight(paths: &Paths<'_>) -> Result<()> {
    check_conflicts(paths)?;
    for parent in [paths.modules, paths.updates] {
        let module = parent.join(MODULE_ID);
        if exists(&module)? {
            ensure!(
                owned(&module)?,
                "Refusing to adopt an external Sui directory"
            );
            ensure!(
                fs::symlink_metadata(module.join("disable"))?.is_file(),
                "Disable Sui before preparing an update"
            );
            ensure!(!exists(&module.join("remove"))?, "Sui removal is pending");
        }
    }
    Ok(())
}

pub(super) fn write_file(path: &Path, bytes: &[u8], mode: u32) -> Result<()> {
    let parent = path.parent().context("Missing Sui parent directory")?;
    fs::create_dir_all(parent)?;
    let mut temporary = tempfile::NamedTempFile::new_in(parent)?;
    temporary
        .as_file()
        .set_permissions(fs::Permissions::from_mode(mode))?;
    temporary.write_all(bytes)?;
    temporary.as_file().sync_all()?;
    temporary.persist(path)?;
    Ok(())
}

/// Reads once into a bounded, validated snapshot, then publishes only disabled content.
pub fn stage_disabled(
    paths: &Paths<'_>,
    read: impl Fn(&str) -> Result<Vec<u8>>,
    abi: &str,
) -> Result<payload::Manifest> {
    let captured = RefCell::new(BTreeMap::new());
    let manifest = payload::validate(
        |name| {
            let bytes = read(name)?;
            captured.borrow_mut().insert(name.to_owned(), bytes.clone());
            Ok(bytes)
        },
        abi,
    )?;
    let captured = captured.into_inner();
    let store = Store::open(paths.transactions, paths.updates, paths.modules)?;
    preflight(paths)?;
    fs::create_dir_all(paths.updates)?;
    fs::create_dir_all(paths.modules)?;
    let staging = store.install(MODULE_ID)?;
    for member in &manifest.files {
        let destination = match member.name.as_str() {
            "zygisk.so" => format!("zygisk/{}.so", manifest.primary_abi),
            "sepolicy.rule" => "licenses/vendor-sepolicy.rule".to_owned(),
            name if name == format!("zygisk.{}.so", manifest.secondary_abi) => {
                format!("zygisk/{}.so", manifest.secondary_abi)
            }
            name => name.to_owned(),
        };
        write_file(
            &staging.module().join(destination),
            &captured[&member.name],
            member.mode,
        )?;
    }
    // Keep provenance, but never load the vendor ADB-root policy as a side effect.
    write_file(
        &staging.module().join("payload.json"),
        &captured["payload.json"],
        0o644,
    )?;
    write_file(
        &staging.module().join("sepolicy.rule"),
        b"# Disabled; explicit enable prepares the minimal managed policy before reboot.\n",
        0o644,
    )?;
    let prop = format!(
        "id={MODULE_ID}\nname=Sui (LunexSU)\nversion={}\nversionCode={}\nauthor=Sui Contributors, FinaSU, LunexSU\ndescription=Managed Sui; permissions are separate from kernel root grants\n",
        manifest.version, manifest.version_code
    );
    write_file(
        &staging.module().join("module.prop"),
        prop.as_bytes(),
        0o644,
    )?;
    write_file(&staging.module().join(OWNER), OWNER_CONTENT, 0o600)?;
    write_file(&staging.module().join("disable"), b"", 0o644)?;
    let pending = paths.updates.join(MODULE_ID);
    let publication = staging.publish_guard(&pending)?;
    let live = paths.modules.join(MODULE_ID);
    fs::create_dir_all(&live)?;
    publication.capture_metadata(&live, &["module.prop", "update", "disable", "remove"])?;
    write_file(&live.join("module.prop"), prop.as_bytes(), 0o644)?;
    write_file(&live.join("update"), b"", 0o644)?;
    write_file(&live.join("disable"), b"", 0o644)?;
    publication.commit_then(|| {
        // Like metamodule aliases, ownership outside the candidate follows commit.
        // A death before this point may require boot recovery, never external adoption.
        write_file(&live.join(OWNER), OWNER_CONTENT, 0o600)?;
        File::open(&live)?.sync_all()?;
        Ok(())
    })?;
    Ok(manifest)
}
