//! Unfinished publications stay ineligible; recovery never discards unidentified payloads.
use anyhow::{Context, Result, ensure};
use std::{
    ffi::CString,
    fs::{self, File, OpenOptions},
    io::Write,
    os::unix::ffi::OsStrExt,
    os::unix::fs::{MetadataExt, PermissionsExt},
    path::{Path, PathBuf},
};

use crate::{file_lock, hot_install::check_run_script};

const PROMOTION_TOKEN: &str = ".ksu-promotion-token";
const PUBLICATION_TOKEN: &str = ".ksu-publication-token";
const PUBLICATION: &str = "publication";
const COMMITTED: &str = "committed";

pub struct Store {
    root: PathBuf,
    updates: PathBuf,
    live: PathBuf,
    _lock: file_lock::FileLock,
}

impl Store {
    pub fn open(root: &Path, updates: &Path, modules: &Path) -> Result<Self> {
        fs::create_dir_all(root)?;
        let lock = file_lock::FileLock::exclusive(&root.join(".lock"))?;
        let store = Self {
            root: root.to_owned(),
            updates: updates.to_owned(),
            live: modules.to_owned(),
            _lock: lock,
        };
        store.recover(updates, modules)?;
        Ok(store)
    }

    fn recover(&self, updates: &Path, modules: &Path) -> Result<()> {
        let entries = fs::read_dir(&self.root)?.collect::<std::io::Result<Vec<_>>>()?;
        for entry in entries {
            let name = entry.file_name();
            let name = name.to_string_lossy();
            if !entry.file_type()?.is_dir() {
                continue;
            }
            let path = entry.path();
            if name.starts_with("gc-install-v2-") || name.starts_with("gc-promote-") {
                // This name is written only after durable commit or a proven rollback.
                sync_dir(&self.root)?;
                fs::remove_dir_all(path)?;
            } else if name.starts_with("install-v2-") {
                recover_install(&path, updates, modules)?;
            } else if name.starts_with("install-") {
                anyhow::bail!(
                    "Unjournaled legacy install retained for inspection: {}",
                    path.display()
                );
            } else if name.starts_with("promote-") {
                recover_promotion(&path, updates, modules)?;
            }
        }
        sync_dir(&self.root)
    }

    pub fn install(&self, module_id: &str) -> Result<Install> {
        validate_id(module_id)?;
        let directory = tempfile::Builder::new()
            .prefix("install-v2-")
            .tempdir_in(&self.root)?;
        let modules = directory.path().join("modules");
        let module = modules.join(module_id);
        fs::create_dir_all(&module)?;
        let tmp = directory.path().join("tmp");
        fs::create_dir(&tmp)?;
        // Never let TempDir's unconditional Drop remove recovery material.
        let directory = directory.keep();
        Ok(Install {
            directory,
            modules,
            module,
            tmp,
            module_id: module_id.to_owned(),
            updates: self.updates.clone(),
            live: self.live.clone(),
        })
    }

    /// Journal the boot promotion before moving a committed update. Recovery
    /// restores a not-yet-promoted update, but never exchanges an old backup back in.
    pub fn promote(&self, update: &Path, live: &Path, flags: &[&str]) -> Result<()> {
        self.promote_with(update, live, flags, replace_dir)
    }

    fn promote_with(
        &self,
        update: &Path,
        live: &Path,
        flags: &[&str],
        replace: impl FnOnce(&Path, &Path) -> Result<()>,
    ) -> Result<()> {
        let id = update
            .file_name()
            .and_then(|id| id.to_str())
            .context("Invalid update path")?;
        validate_id(id)?;
        let updates = update.parent().context("Missing updates directory")?;
        let modules = live.parent().context("Missing modules directory")?;
        ensure!(
            fs::symlink_metadata(update)?.is_dir(),
            "Update is not a real directory"
        );
        // Promotion journals are explicitly managed; Drop must never remove
        // recovery material after a mutation or an uncertain sync result.
        let directory = tempfile::Builder::new()
            .prefix("promote-")
            .tempdir_in(&self.root)?
            .keep();
        let token = directory
            .file_name()
            .context("Invalid journal path")?
            .as_bytes();
        let payload = directory.join("payload");
        write_synced(&directory.join("module-id"), id.as_bytes())?;
        write_synced(&update.join(PROMOTION_TOKEN), token)?;
        sync_dir(update)?;
        sync_dir(&directory)?;
        sync_dir(&self.root)?;
        fs::rename(update, &payload)?;
        let result = (|| {
            // Removal takes precedence over disable, just as in the old boot path.
            for flag in flags {
                if live.join(flag).exists() {
                    OpenOptions::new()
                        .write(true)
                        .create(true)
                        .truncate(false)
                        .open(payload.join(flag))?
                        .sync_all()?;
                    break;
                }
            }
            sync_dir(&payload)?;
            sync_dir(&directory)?;
            sync_dir(updates)?;
            replace(&payload, live)
        })();
        if let Err(error) = result {
            if let Err(rollback) = recover_promotion(&directory, updates, modules) {
                return Err(error).context(format!(
                    "Update retained at {} after rollback failed: {rollback}",
                    directory.display()
                ));
            }
            return Err(error);
        }
        // From this point the new module is visible. Sync/cleanup failures must
        // not be reported as a failed promotion (and trigger an incorrect rollback).
        if let Err(error) = recover_promotion(&directory, updates, modules) {
            log::warn!("Promotion durability/cleanup will be retried: {error:#}");
        }
        Ok(())
    }
}

fn recover_promotion(directory: &Path, updates: &Path, modules: &Path) -> Result<()> {
    let Some(bytes) = read_optional(&directory.join("module-id"))? else {
        // Only an empty, never-started journal is disposable without an ID.
        // Missing/partially deleted records cannot authorize deleting payloads.
        ensure!(
            fs::read_dir(directory)?.next().is_none(),
            "Incomplete boot journal retained: {}",
            directory.display()
        );
        return discard_transaction(directory);
    };
    let id = std::str::from_utf8(&bytes)?;
    validate_id(id)?;
    let token = directory
        .file_name()
        .context("Invalid promotion journal")?
        .as_bytes();
    let live = modules.join(id);
    let payload = directory.join("payload");
    let committed = read_optional(&live.join(PROMOTION_TOKEN))?.as_deref() == Some(token);
    if !committed && directory_id(&payload)?.is_some() {
        // A missing/unreadable live token must not turn an old backup into an update.
        ensure!(
            read_optional(&payload.join(PROMOTION_TOKEN))?.as_deref() == Some(token),
            "Unidentified boot payload retained: {}",
            directory.display()
        );
        fs::create_dir_all(updates)?;
        let update = updates.join(id);
        ensure!(
            directory_id(&update)?.is_none(),
            "Conflicting boot promotion retained: {}",
            directory.display()
        );
        fs::rename(&payload, update)?;
    }
    // Both rename parents must be durable, even on re-entry with no payload
    // after an earlier rollback rename whose sync failed. Keep the live token
    // and old backup until the promotion's two parents are confirmed as well.
    sync_dir(directory)?;
    sync_dir(if committed { modules } else { updates })?;
    discard_transaction(directory)?;
    if committed {
        fs::remove_file(live.join(PROMOTION_TOKEN)).ok();
    }
    Ok(())
}

pub struct Install {
    directory: PathBuf,
    modules: PathBuf,
    module: PathBuf,
    tmp: PathBuf,
    module_id: String,
    updates: PathBuf,
    live: PathBuf,
}

impl Install {
    pub fn module(&self) -> &Path {
        &self.module
    }
    pub fn modules(&self) -> &Path {
        &self.modules
    }
    pub fn tmp(&self) -> &Path {
        &self.tmp
    }

    pub fn ready_for_hot_install(&self) -> bool {
        // Hot install must not consume the candidate while a journal still needs it.
        matches!(self.directory.try_exists(), Ok(false))
    }

    /// Resolve a requested script while absolute symlinks into the private
    /// staging directory still work, before publishing that directory elsewhere.
    pub fn prepare_hot_request(&self, marker_name: &str) -> Result<()> {
        let marker = self.module.join(marker_name);
        let Ok(content) = fs::read_to_string(&marker) else {
            return Ok(());
        };
        let script = content.trim();
        if !script.is_empty() {
            match check_run_script(&self.module, script) {
                Ok(Some(relative)) => {
                    if let Some(relative) = relative.to_str() {
                        write_synced(&marker, relative.as_bytes())?;
                    }
                }
                Ok(None) => write_synced(&marker, b"")?,
                // Preserve invalid requests for hot_install::apply's usual
                // validation message and fallback-to-reboot behavior.
                Err(_) => {}
            }
        }
        Ok(())
    }

    /// Only call after extraction, the installer and post-install checks succeed.
    /// Until durably committed, errors/death use identity-checked rollback.
    /// Failed rollback retains the journal and all recovery material.
    pub fn publish_guard(&self, destination: &Path) -> Result<Publication<'_>> {
        ensure!(
            destination == self.updates.join(&self.module_id),
            "Unexpected publication destination"
        );
        ensure!(
            !has_recovery_hints(&self.directory)?,
            "Publication already started"
        );
        let record = PublicationRecord {
            module_id: self.module_id.clone(),
            new: directory_id(&self.module)?.context("Missing private module")?,
            previous: directory_id(destination)?,
        };
        ensure!(
            record.previous != Some(record.new),
            "Source and destination are the same module"
        );
        write_synced(&self.module.join(PUBLICATION_TOKEN), self.token()?)?;
        sync_tree(&self.module)?;
        sync_dir(&self.modules)?;
        // The started marker makes a missing/corrupt journal ambiguous, not disposable.
        write_synced(&self.directory.join("publication-started"), self.token()?)?;
        sync_dir(&self.directory)?;
        write_synced(
            &self.directory.join(PUBLICATION),
            record.encode().as_bytes(),
        )?;
        sync_dir(&self.directory)?;
        sync_dir(
            self.directory
                .parent()
                .context("Missing transaction root")?,
        )?;
        #[cfg(test)]
        pause_for_test("publication-journal");

        if let Err(error) = replace_dir(&self.module, destination) {
            if let Err(recovery) = recover_install(&self.directory, &self.updates, &self.live) {
                log::warn!(
                    "Publication error retained at {}: {recovery:#}",
                    self.directory.display()
                );
            }
            return Err(error);
        }
        #[cfg(test)]
        pause_for_test("publication-swap");
        // A failed sync must retain the already-written intent and both payloads.
        sync_dir(&self.modules)?;
        sync_dir(&self.updates)?;
        Ok(Publication {
            install: self,
            recover_on_drop: true,
        })
    }

    #[cfg(test)]
    pub fn publish(&self, destination: &Path) -> Result<()> {
        self.publish_guard(destination)?.commit()
    }

    fn token(&self) -> Result<&[u8]> {
        Ok(self
            .directory
            .file_name()
            .context("Invalid transaction path")?
            .as_bytes())
    }
}

impl Drop for Install {
    fn drop(&mut self) {
        // A published transaction is cleaned ONLY by identity-checked recovery.
        // In particular a rollback/commit I/O error must not run remove_dir_all.
        if matches!(has_recovery_hints(&self.directory), Ok(false))
            && let Err(error) = discard_transaction(&self.directory)
        {
            log::warn!(
                "Failed to clean private staging {}: {error:#}",
                self.directory.display()
            );
        }
    }
}

#[must_use]
pub struct Publication<'a> {
    install: &'a Install,
    recover_on_drop: bool,
}

impl Publication<'_> {
    pub fn capture_metadata(&self, directory: &Path, names: &[&str]) -> Result<()> {
        ensure!(
            directory == self.install.live.join(&self.install.module_id),
            "Unexpected live directory"
        );
        capture_metadata(&self.install.directory, directory, names)
    }

    pub fn commit(mut self) -> Result<()> {
        // Once a commit write starts, an error has an uncertain durable outcome.
        // Leave it for recovery, never guess based on an in-memory bool or Drop.
        self.recover_on_drop = false;
        sync_metadata(
            &self.install.directory,
            &self.install.live.join(&self.install.module_id),
        )?;
        write_synced(
            &self.install.directory.join(COMMITTED),
            self.install.token()?,
        )?;
        sync_dir(&self.install.directory)?;
        #[cfg(test)]
        pause_for_test("publication-commit");
        if let Err(error) = recover_install(
            &self.install.directory,
            &self.install.updates,
            &self.install.live,
        ) {
            log::warn!(
                "Committed install retained for cleanup at {}: {error:#}",
                self.install.directory.display()
            );
        }
        Ok(())
    }
}

impl Drop for Publication<'_> {
    fn drop(&mut self) {
        if !self.recover_on_drop {
            return;
        }
        if let Err(error) = recover_install(
            &self.install.directory,
            &self.install.updates,
            &self.install.live,
        ) {
            log::error!(
                "Install rollback retained at {}: {error:#}",
                self.install.directory.display()
            );
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct DirectoryId {
    device: u64,
    inode: u64,
}

impl DirectoryId {
    fn encode(self) -> String {
        format!("{}:{}", self.device, self.inode)
    }

    fn decode(value: &str) -> Result<Self> {
        let (device, inode) = value
            .split_once(':')
            .context("Invalid directory identity")?;
        Ok(Self {
            device: device.parse()?,
            inode: inode.parse()?,
        })
    }
}

fn directory_id(path: &Path) -> Result<Option<DirectoryId>> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            ensure!(
                metadata.is_dir(),
                "Not a real module directory: {}",
                path.display()
            );
            Ok(Some(DirectoryId {
                device: metadata.dev(),
                inode: metadata.ino(),
            }))
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error.into()),
    }
}

struct PublicationRecord {
    module_id: String,
    new: DirectoryId,
    previous: Option<DirectoryId>,
}

impl PublicationRecord {
    fn encode(&self) -> String {
        format!(
            "publication-v2\n{}\n{}\n{}\n",
            self.module_id,
            self.new.encode(),
            self.previous
                .map_or_else(|| "none".to_owned(), DirectoryId::encode)
        )
    }

    fn decode(bytes: &[u8]) -> Result<Self> {
        let text = std::str::from_utf8(bytes)?;
        let lines: Vec<_> = text.lines().collect();
        ensure!(
            lines.len() == 4 && lines[0] == "publication-v2",
            "Invalid publication journal"
        );
        validate_id(lines[1])?;
        let new = DirectoryId::decode(lines[2])?;
        let previous = if lines[3] == "none" {
            None
        } else {
            Some(DirectoryId::decode(lines[3])?)
        };
        ensure!(previous != Some(new), "Ambiguous publication identities");
        Ok(Self {
            module_id: lines[1].to_owned(),
            new,
            previous,
        })
    }
}

fn read_optional(path: &Path) -> Result<Option<Vec<u8>>> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            ensure!(
                metadata.is_file(),
                "Journal entry is not a regular file: {}",
                path.display()
            );
            Ok(Some(fs::read(path)?))
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error.into()),
    }
}

fn has_recovery_hints(directory: &Path) -> Result<bool> {
    for name in [
        "publication-started",
        PUBLICATION,
        COMMITTED,
        "metadata-ready",
        "metadata",
    ] {
        match fs::symlink_metadata(directory.join(name)) {
            // A dangling symlink or malformed entry is evidence, not absence.
            Ok(_) => return Ok(true),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
    }
    Ok(false)
}

/// All update consumers must ignore provisional candidates, even before recovery
/// or when recovery fails closed on an ambiguous/failed rollback.
pub fn is_pending_publication(module: &Path) -> bool {
    !matches!(fs::symlink_metadata(module.join(PUBLICATION_TOKEN)),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound)
}

fn recover_install(directory: &Path, updates: &Path, live: &Path) -> Result<()> {
    let Some(bytes) = read_optional(&directory.join(PUBLICATION))? else {
        ensure!(
            !has_recovery_hints(directory)?,
            "Missing publication journal; retained {}",
            directory.display()
        );
        return discard_transaction(directory);
    };
    let record = PublicationRecord::decode(&bytes)
        .with_context(|| format!("Invalid journal retained at {}", directory.display()))?;
    let source = directory.join("modules").join(&record.module_id);
    let destination = updates.join(&record.module_id);
    let source_id = directory_id(&source)?;
    let destination_id = directory_id(&destination)?;
    let token = directory
        .file_name()
        .context("Invalid journal directory")?
        .as_bytes();
    let committed = match read_optional(&directory.join(COMMITTED))? {
        Some(contents) => {
            ensure!(
                contents == token,
                "Invalid commit marker; retained {}",
                directory.display()
            );
            true
        }
        None => false,
    };
    if committed {
        ensure!(
            destination_id == Some(record.new) && source_id == record.previous,
            "Ambiguous committed publication; retained {}",
            directory.display()
        );
        // A previous commit fsync may have returned an uncertain I/O error.
        // Confirm its directory entry before enabling the new candidate.
        sync_dir(directory)?;
        // Only the durable commit authorizes discarding a superseded pending copy.
        let marker = destination.join(PUBLICATION_TOKEN);
        if let Some(contents) = read_optional(&marker)? {
            ensure!(
                contents == token,
                "Unexpected publication token; retained {}",
                directory.display()
            );
            fs::remove_file(marker)?;
        }
        sync_dir(&destination)?;
    } else {
        let before_publish = source_id == Some(record.new) && destination_id == record.previous;
        let after_publish = source_id == record.previous && destination_id == Some(record.new);
        ensure!(
            before_publish || after_publish,
            "Ambiguous uncommitted publication; retained {}",
            directory.display()
        );
        restore_metadata(directory, &live.join(&record.module_id))?;
        if after_publish {
            #[cfg(test)]
            ensure!(
                std::env::var_os("MODULE_SAFETY_FAIL_ROLLBACK").is_none(),
                "Injected rollback failure"
            );
            if record.previous.is_some() {
                replace_dir(&source, &destination)?;
            } else {
                fs::rename(&destination, &source)?;
            }
            #[cfg(test)]
            pause_for_test("publication-rollback");
        }
        ensure!(
            directory_id(&source)? == Some(record.new)
                && directory_id(&destination)? == record.previous,
            "Rollback identities changed; retained {}",
            directory.display()
        );
    }
    // Also sync on re-entry after a rollback whose earlier sync failed. Never
    // delete the candidate before the restored/new destination is durable.
    sync_dir(&directory.join("modules"))?;
    sync_dir(updates)?;
    discard_transaction(directory)
}

/// Classify garbage by a synced directory rename before recursive deletion.
/// Partial cleanup cannot turn an ambiguous journal into disposable data.
fn discard_transaction(directory: &Path) -> Result<()> {
    if !directory.try_exists()? {
        return Ok(());
    }
    let root = directory.parent().context("Missing transaction root")?;
    let name = directory
        .file_name()
        .context("Invalid transaction name")?
        .to_string_lossy();
    ensure!(
        name.starts_with("install-v2-") || name.starts_with("promote-"),
        "Refusing to discard unknown transaction"
    );
    let garbage = root.join(format!("gc-{name}"));
    ensure!(
        !garbage.try_exists()?,
        "Conflicting cleanup directory; retained {}",
        directory.display()
    );
    fs::rename(directory, &garbage)?;
    sync_dir(root)?;
    #[cfg(test)]
    pause_for_test(if name.starts_with("promote-") {
        "promotion-garbage"
    } else {
        "publication-garbage"
    });
    fs::remove_dir_all(garbage)?;
    sync_dir(root)
}

fn validate_metadata_names(names: &[&str]) -> Result<()> {
    ensure!(
        !names.is_empty() && names.len() <= 4,
        "Invalid metadata manifest"
    );
    for (index, name) in names.iter().enumerate() {
        ensure!(
            matches!(*name, "module.prop" | "update" | "disable" | "remove")
                && !names[..index].contains(name),
            "Invalid metadata name"
        );
    }
    Ok(())
}

fn capture_metadata(journal: &Path, live: &Path, names: &[&str]) -> Result<()> {
    validate_metadata_names(names)?;
    ensure!(
        !journal.join("metadata-ready").try_exists()?,
        "Metadata already captured"
    );
    let identity = directory_id(live)?.context("Missing live metadata directory")?;
    let backup = journal.join("metadata");
    fs::create_dir_all(&backup)?;
    for name in names {
        let path = live.join(name);
        let mode = match fs::symlink_metadata(&path) {
            Ok(metadata) => {
                ensure!(metadata.is_file(), "Live metadata is not a regular file");
                write_synced(&backup.join(format!("data-{name}")), &fs::read(&path)?)?;
                (metadata.permissions().mode() & 0o7777).to_string()
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => "absent".to_owned(),
            Err(error) => return Err(error.into()),
        };
        write_synced(&backup.join(format!("mode-{name}")), mode.as_bytes())?;
    }
    sync_dir(&backup)?;
    let manifest = format!("metadata-v2\n{}\n{}\n", identity.encode(), names.join("\n"));
    write_synced(&journal.join("metadata-ready"), manifest.as_bytes())?;
    sync_dir(journal)
}

fn metadata_manifest(journal: &Path, live: &Path) -> Result<Option<Vec<String>>> {
    let Some(bytes) = read_optional(&journal.join("metadata-ready"))? else {
        return Ok(None);
    };
    let text = std::str::from_utf8(&bytes)?;
    let lines: Vec<_> = text.lines().collect();
    ensure!(
        lines.len() >= 3 && lines[0] == "metadata-v2",
        "Invalid metadata journal"
    );
    let identity = DirectoryId::decode(lines[1])?;
    ensure!(
        directory_id(live)? == Some(identity),
        "Live payload changed; metadata journal retained"
    );
    validate_metadata_names(&lines[2..])?;
    Ok(Some(
        lines[2..].iter().map(|name| (*name).to_owned()).collect(),
    ))
}

fn restore_metadata(journal: &Path, live: &Path) -> Result<()> {
    let Some(names) = metadata_manifest(journal, live)? else {
        return Ok(());
    };
    for name in names {
        let path = live.join(&name);
        match fs::symlink_metadata(&path) {
            Ok(metadata) => ensure!(
                metadata.is_file(),
                "Unexpected live metadata type; journal retained"
            ),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
        let mode = fs::read_to_string(journal.join("metadata").join(format!("mode-{name}")))?;
        if mode == "absent" {
            match fs::remove_file(&path) {
                Ok(()) => {}
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => return Err(error.into()),
            }
        } else {
            let mode: u32 = mode.parse()?;
            ensure!(mode <= 0o7777, "Invalid saved metadata permissions");
            let contents = fs::read(journal.join("metadata").join(format!("data-{name}")))?;
            let mut file = tempfile::NamedTempFile::new_in(live)?;
            file.write_all(&contents)?;
            file.as_file()
                .set_permissions(fs::Permissions::from_mode(mode))?;
            file.as_file().sync_all()?;
            file.persist(path)?;
        }
    }
    sync_dir(live)
}

fn sync_metadata(journal: &Path, live: &Path) -> Result<()> {
    let Some(names) = metadata_manifest(journal, live)? else {
        return Ok(());
    };
    for name in names {
        let path = live.join(name);
        match fs::symlink_metadata(&path) {
            Ok(metadata) => {
                ensure!(metadata.is_file(), "Unexpected live metadata type");
                File::open(path)?.sync_all()?;
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
    }
    sync_dir(live)
}

fn sync_tree(directory: &Path) -> Result<()> {
    for entry in fs::read_dir(directory)? {
        let entry = entry?;
        if entry.file_type()?.is_dir() {
            sync_tree(&entry.path())?;
        } else if entry.file_type()?.is_file() {
            File::open(entry.path())?.sync_all()?;
        }
    }
    sync_dir(directory)
}

#[cfg(test)]
fn pause_for_test(point: &str) {
    if std::env::var("MODULE_SAFETY_PAUSE_AT").as_deref() == Ok(point) {
        let fixture =
            PathBuf::from(std::env::var_os("MODULE_SAFETY_FIXTURE").expect("Missing fixture"));
        write_synced(&fixture.join("paused"), point.as_bytes())
            .expect("Signal publication checkpoint");
        loop {
            std::thread::park();
        }
    }
}

fn validate_id(id: &str) -> Result<()> {
    ensure!(
        id.len() >= 2
            && id.as_bytes()[0].is_ascii_alphabetic()
            && id
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || b"._-".contains(&c)),
        "Invalid transaction module ID"
    );
    Ok(())
}

fn write_synced(path: &Path, contents: &[u8]) -> Result<()> {
    let parent = path.parent().context("Missing journal directory")?;
    let mut file = tempfile::NamedTempFile::new_in(parent)?;
    file.write_all(contents)?;
    file.as_file().sync_all()?;
    file.persist(path)?;
    Ok(())
}

fn sync_dir(path: &Path) -> Result<()> {
    #[cfg(test)]
    tests::check_sync(path)?;
    File::open(path)?.sync_all()?;
    Ok(())
}

/// Never remove the old directory first. Exchange is atomic on Android/Linux;
/// unsupported filesystems/kernels fail closed, leaving the working module intact.
fn replace_dir(source: &Path, destination: &Path) -> Result<()> {
    ensure!(
        fs::symlink_metadata(source)?.is_dir(),
        "Module source is not a directory"
    );
    if !destination.try_exists()? {
        return fs::rename(source, destination).context("Failed to publish module directory");
    }
    ensure!(
        fs::symlink_metadata(destination)?.is_dir(),
        "Module destination is not a directory"
    );
    let source = CString::new(source.as_os_str().as_bytes())?;
    let destination = CString::new(destination.as_os_str().as_bytes())?;
    let result = unsafe {
        libc::syscall(
            libc::SYS_renameat2,
            libc::AT_FDCWD,
            source.as_ptr(),
            libc::AT_FDCWD,
            destination.as_ptr(),
            2, // RENAME_EXCHANGE
        )
    };
    if result != 0 {
        return Err(std::io::Error::last_os_error())
            .context("Failed to exchange module directories");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    thread_local! {
        static SYNC_FAILURE: RefCell<Option<(PathBuf, usize)>> = const { RefCell::new(None) };
    }

    pub(super) fn check_sync(path: &Path) -> Result<()> {
        SYNC_FAILURE.with_borrow_mut(|failure| {
            if let Some((target, hits)) = failure
                && target == path
            {
                *hits += 1;
                anyhow::bail!("Injected directory sync failure: {}", path.display());
            }
            Ok(())
        })
    }

    struct SyncFailure;

    impl SyncFailure {
        fn at(path: &Path) -> Self {
            SYNC_FAILURE.with_borrow_mut(|failure| {
                assert!(failure.is_none());
                *failure = Some((path.to_owned(), 0));
            });
            Self
        }

        fn hits() -> usize {
            SYNC_FAILURE.with_borrow(|failure| failure.as_ref().unwrap().1)
        }
    }

    impl Drop for SyncFailure {
        fn drop(&mut self) {
            SYNC_FAILURE.with_borrow_mut(|failure| *failure = None);
        }
    }

    struct PromotionFixture {
        temp: tempfile::TempDir,
        new: DirectoryId,
        old: Option<DirectoryId>,
    }

    impl PromotionFixture {
        fn new(previous: bool) -> Self {
            let fixtures = std::env::var_os("KSUD_MODULE_FIXTURE_ROOT").map_or_else(
                || Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures"),
                PathBuf::from,
            );
            fs::create_dir_all(&fixtures).unwrap();
            let temp = tempfile::tempdir_in(fixtures).unwrap();
            let update = temp.path().join("modules_update/example");
            let live = temp.path().join("modules/example");
            fs::create_dir_all(&update).unwrap();
            fs::create_dir_all(live.parent().unwrap()).unwrap();
            fs::write(update.join("version"), "new").unwrap();
            if previous {
                fs::create_dir(&live).unwrap();
                fs::write(live.join("version"), "old").unwrap();
            }
            Self {
                new: directory_id(&update).unwrap().unwrap(),
                old: directory_id(&live).unwrap(),
                temp,
            }
        }

        fn root(&self) -> PathBuf {
            self.temp.path().join("transactions")
        }

        fn update(&self) -> PathBuf {
            self.temp.path().join("modules_update/example")
        }

        fn live(&self) -> PathBuf {
            self.temp.path().join("modules/example")
        }

        fn open(&self) -> Result<Store> {
            Store::open(
                &self.root(),
                self.update().parent().unwrap(),
                self.live().parent().unwrap(),
            )
        }

        fn journal(&self, garbage: bool) -> PathBuf {
            let entries: Vec<_> = fs::read_dir(self.root())
                .unwrap()
                .map(|entry| entry.unwrap())
                .filter(|entry| entry.file_type().unwrap().is_dir())
                .collect();
            assert_eq!(entries.len(), 1);
            assert!(
                entries[0]
                    .file_name()
                    .to_str()
                    .unwrap()
                    .starts_with(if garbage { "gc-promote-" } else { "promote-" })
            );
            entries[0].path()
        }

        fn assert_payloads(&self, journal: Option<&Path>, promoted: bool) {
            assert_eq!(
                directory_id(&self.live()).unwrap(),
                if promoted { Some(self.new) } else { self.old }
            );
            if promoted || self.old.is_some() {
                assert_eq!(
                    fs::read_to_string(self.live().join("version")).unwrap(),
                    if promoted { "new" } else { "old" }
                );
            }
            assert_eq!(
                directory_id(&self.update()).unwrap(),
                if promoted { None } else { Some(self.new) }
            );
            if !promoted {
                assert_eq!(
                    fs::read_to_string(self.update().join("version")).unwrap(),
                    "new"
                );
            }
            if let Some(journal) = journal {
                assert_eq!(
                    directory_id(&journal.join("payload")).unwrap(),
                    if promoted { self.old } else { None }
                );
                if promoted && self.old.is_some() {
                    assert_eq!(
                        fs::read_to_string(journal.join("payload/version")).unwrap(),
                        "old"
                    );
                }
            }
        }

        fn assert_retained(&self, journal: &Path, promoted: bool) {
            assert_eq!(
                fs::read_to_string(journal.join("module-id")).unwrap(),
                "example"
            );
            let module = if promoted { self.live() } else { self.update() };
            assert_eq!(
                fs::read(module.join(PROMOTION_TOKEN)).unwrap(),
                journal.file_name().unwrap().as_bytes()
            );
            self.assert_payloads(Some(journal), promoted);
        }

        fn recover_twice(&self, promoted: bool) {
            for _ in 0..2 {
                let _store = self.open().unwrap();
                self.assert_payloads(None, promoted);
                assert_eq!(fs::read_dir(self.root()).unwrap().count(), 1);
            }
        }
    }

    #[test]
    fn failed_boot_exchange_restores_update_and_keeps_working_module() {
        for previous in [false, true] {
            let fixture = PromotionFixture::new(previous);
            let store = fixture.open().unwrap();
            assert!(
                store
                    .promote_with(&fixture.update(), &fixture.live(), &[], |_, _| {
                        anyhow::bail!("injected exchange failure")
                    })
                    .is_err()
            );
            drop(store);
            fixture.recover_twice(false);
        }
    }

    #[test]
    fn promotion_parent_sync_failures_preserve_success_and_both_payloads() {
        for previous in [false, true] {
            for parent in ["journal", "modules"] {
                let fixture = PromotionFixture::new(previous);
                let store = fixture.open().unwrap();
                let mut failure = None;
                store
                    .promote_with(&fixture.update(), &fixture.live(), &[], |source, target| {
                        replace_dir(source, target)?;
                        failure = Some(SyncFailure::at(if parent == "journal" {
                            source.parent().unwrap()
                        } else {
                            target.parent().unwrap()
                        }));
                        Ok(())
                    })
                    .unwrap();
                let journal = fixture.journal(false);
                fixture.assert_retained(&journal, true);
                drop(store);
                for _ in 0..2 {
                    assert!(fixture.open().is_err());
                    fixture.assert_retained(&journal, true);
                }
                assert_eq!(SyncFailure::hits(), 3);
                drop(failure);
                fixture.recover_twice(true);
                assert!(!fixture.live().join(PROMOTION_TOKEN).exists());
            }
        }
    }

    #[test]
    fn rollback_parent_sync_failures_retain_journal_even_after_payload_moved_back() {
        for previous in [false, true] {
            for parent in ["journal", "updates"] {
                let fixture = PromotionFixture::new(previous);
                let store = fixture.open().unwrap();
                let mut failure = None;
                let error = store
                    .promote_with(&fixture.update(), &fixture.live(), &[], |source, _| {
                        let update = fixture.update();
                        failure = Some(SyncFailure::at(if parent == "journal" {
                            source.parent().unwrap()
                        } else {
                            update.parent().unwrap()
                        }));
                        anyhow::bail!("injected exchange failure")
                    })
                    .unwrap_err();
                assert!(format!("{error:#}").contains("injected exchange failure"));
                let journal = fixture.journal(false);
                fixture.assert_retained(&journal, false);
                drop(store);
                for _ in 0..2 {
                    assert!(fixture.open().is_err());
                    fixture.assert_retained(&journal, false);
                }
                assert_eq!(SyncFailure::hits(), 3);
                drop(failure);
                fixture.recover_twice(false);
            }
        }
    }

    #[test]
    fn promotion_garbage_is_synced_before_deletion_and_survives_partial_cleanup() {
        for previous in [false, true] {
            for promoted in [false, true] {
                let fixture = PromotionFixture::new(previous);
                let store = fixture.open().unwrap();
                let mut failure = None;
                let result = store.promote_with(
                    &fixture.update(),
                    &fixture.live(),
                    &[],
                    |source, target| {
                        if promoted {
                            replace_dir(source, target)?;
                        }
                        failure = Some(SyncFailure::at(&fixture.root()));
                        ensure!(promoted, "injected exchange failure");
                        Ok(())
                    },
                );
                assert_eq!(result.is_ok(), promoted);
                let garbage = fixture.journal(true);
                assert!(garbage.join("module-id").is_file());
                fixture.assert_payloads(Some(&garbage), promoted);
                drop(store);
                for _ in 0..2 {
                    assert!(fixture.open().is_err());
                    assert!(garbage.join("module-id").is_file());
                    fixture.assert_payloads(Some(&garbage), promoted);
                }
                assert_eq!(SyncFailure::hits(), 3);
                drop(failure);
                // Simulate interrupted recursive deletion AFTER durable classification.
                sync_dir(&fixture.root()).unwrap();
                fs::remove_file(garbage.join("module-id")).unwrap();
                let failure = SyncFailure::at(&fixture.root());
                for _ in 0..2 {
                    assert!(fixture.open().is_err());
                    assert!(garbage.exists());
                    fixture.assert_payloads(Some(&garbage), promoted);
                }
                assert_eq!(SyncFailure::hits(), 2);
                drop(failure);
                fixture.recover_twice(promoted);
            }
        }
    }

    #[test]
    fn ambiguous_promotion_records_and_tokens_retain_all_material() {
        for corruption in [
            "missing-id",
            "invalid-id",
            "dangling-id",
            "missing-token",
            "invalid-token",
        ] {
            let fixture = PromotionFixture::new(true);
            let store = fixture.open().unwrap();
            let mut failure = None;
            store
                .promote_with(&fixture.update(), &fixture.live(), &[], |source, target| {
                    replace_dir(source, target)?;
                    failure = Some(SyncFailure::at(target.parent().unwrap()));
                    Ok(())
                })
                .unwrap();
            let journal = fixture.journal(false);
            drop(failure);
            drop(store);
            match corruption {
                "missing-id" => fs::remove_file(journal.join("module-id")).unwrap(),
                "invalid-id" => fs::write(journal.join("module-id"), "../invalid").unwrap(),
                "dangling-id" => {
                    fs::remove_file(journal.join("module-id")).unwrap();
                    std::os::unix::fs::symlink("missing", journal.join("module-id")).unwrap();
                }
                "missing-token" => fs::remove_file(fixture.live().join(PROMOTION_TOKEN)).unwrap(),
                "invalid-token" => {
                    fs::remove_file(fixture.live().join(PROMOTION_TOKEN)).unwrap();
                    fs::create_dir(fixture.live().join(PROMOTION_TOKEN)).unwrap();
                }
                _ => unreachable!(),
            }
            for _ in 0..2 {
                assert!(fixture.open().is_err());
                fixture.assert_payloads(Some(&journal), true);
                assert!(journal.exists());
            }
        }
        let fixture = PromotionFixture::new(false);
        let store = fixture.open().unwrap();
        let journal = fixture.root().join("promote-incomplete");
        fs::create_dir(&journal).unwrap();
        fs::write(
            journal.join("unidentified"),
            "retain incomplete journal data",
        )
        .unwrap();
        drop(store);
        for _ in 0..2 {
            assert!(fixture.open().is_err());
            assert_eq!(
                fs::read_to_string(journal.join("unidentified")).unwrap(),
                "retain incomplete journal data"
            );
            fixture.assert_payloads(None, false);
        }
    }
}
