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
            if name.starts_with("gc-install-v2-") {
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
                let id_file = path.join("module-id");
                if !id_file.exists() {
                    ensure!(
                        !path.join("payload").try_exists()?,
                        "Incomplete boot journal with payload retained: {}",
                        path.display()
                    );
                    // Interrupted before the journal was complete and before moving anything.
                    fs::remove_dir_all(path)?;
                    continue;
                }
                let id = fs::read_to_string(id_file)?;
                validate_id(&id)?;
                let live = modules.join(&id);
                let payload = path.join("payload");
                let committed = fs::read(live.join(PROMOTION_TOKEN))
                    .is_ok_and(|token| token == name.as_bytes());
                if !committed && payload.exists() {
                    fs::create_dir_all(updates)?;
                    let update = updates.join(&id);
                    if update.exists() {
                        anyhow::bail!("Conflicting boot promotion retained: {}", path.display());
                    }
                    fs::rename(&payload, update)?;
                    sync_dir(updates)?;
                }
                fs::remove_dir_all(path)?;
                if committed {
                    // The backup/journal must be durably gone before dropping
                    // the token that distinguishes an old backup from an update.
                    sync_dir(&self.root)?;
                    fs::remove_file(live.join(PROMOTION_TOKEN)).ok();
                }
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
        ensure!(
            fs::symlink_metadata(update)?.is_dir(),
            "Update is not a real directory"
        );
        let directory = tempfile::Builder::new()
            .prefix("promote-")
            .tempdir_in(&self.root)?;
        let token = directory
            .path()
            .file_name()
            .context("Invalid journal path")?
            .as_bytes();
        let payload = directory.path().join("payload");
        write_synced(&directory.path().join("module-id"), id.as_bytes())?;
        write_synced(&update.join(PROMOTION_TOKEN), token)?;
        sync_dir(update)?;
        sync_dir(directory.path())?;
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
            sync_dir(directory.path())?;
            sync_dir(update.parent().context("Missing updates directory")?)?;
            replace(&payload, live)
        })();
        if let Err(error) = result {
            if let Err(rollback) = fs::rename(&payload, update) {
                // Keep the journal for the next locked recovery attempt.
                let kept = directory.keep();
                return Err(error).context(format!(
                    "Update retained at {} after rollback failed: {rollback}",
                    kept.display()
                ));
            }
            return Err(error);
        }
        // From this point the new module is committed. Sync/cleanup failures must
        // not be reported as a failed promotion (and trigger an incorrect rollback).
        if let Err(error) = sync_dir(live.parent().context("Missing modules directory")?) {
            log::warn!("Failed to sync module promotion: {error:#}");
        }
        if let Err(error) = directory.close() {
            log::warn!("Promotion cleanup will be retried: {error}");
        } else if let Err(error) = sync_dir(&self.root) {
            // Retain the token if durable journal removal could not be confirmed.
            log::warn!("Failed to sync promotion cleanup: {error:#}");
        } else {
            fs::remove_file(live.join(PROMOTION_TOKEN)).ok();
        }
        Ok(())
    }
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
            && let Err(error) = discard_install(&self.directory)
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
        return discard_install(directory);
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
    discard_install(directory)
}

/// Classify garbage by a synced directory rename before recursive deletion.
/// Partial cleanup cannot turn an ambiguous install journal into disposable data.
fn discard_install(directory: &Path) -> Result<()> {
    if !directory.try_exists()? {
        return Ok(());
    }
    let root = directory.parent().context("Missing transaction root")?;
    let name = directory
        .file_name()
        .context("Invalid transaction name")?
        .to_string_lossy();
    ensure!(
        name.starts_with("install-v2-"),
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
    pause_for_test("publication-garbage");
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
        fs::write(fixture.join("paused"), point).expect("Signal publication checkpoint");
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

    #[test]
    fn failed_boot_exchange_restores_update_and_keeps_working_module() {
        let fixtures = Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures");
        fs::create_dir_all(&fixtures).unwrap();
        let temp = tempfile::tempdir_in(fixtures).unwrap();
        let updates = temp.path().join("modules_update");
        let modules = temp.path().join("modules");
        let update = updates.join("example");
        let live = modules.join("example");
        fs::create_dir_all(&update).unwrap();
        fs::create_dir_all(&live).unwrap();
        fs::write(update.join("version"), "new").unwrap();
        fs::write(live.join("version"), "old").unwrap();
        let store = Store::open(&temp.path().join("transactions"), &updates, &modules).unwrap();
        assert!(
            store
                .promote_with(&update, &live, &[], |_, _| {
                    anyhow::bail!("injected exchange failure")
                })
                .is_err()
        );
        assert_eq!(fs::read_to_string(live.join("version")).unwrap(), "old");
        assert_eq!(fs::read_to_string(update.join("version")).unwrap(), "new");
    }
}
