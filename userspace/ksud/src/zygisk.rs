//! Coexistence policy for the two known standalone injector module IDs.
//! Never identify providers by a `zygisk/` plugin directory or a display name.
use std::{
    collections::BTreeMap,
    ffi::CString,
    fs::{File, OpenOptions},
    io,
    os::{
        fd::{AsRawFd, FromRawFd},
        unix::fs::OpenOptionsExt,
    },
    path::{Path, PathBuf},
};

pub const PROVIDERS: [&str; 2] = ["rezygisk", "zygisksu"];
const OWNER: &str = ".lunexsu_builtin_zygisk";
const ADDON: &str = "zygisknextsu";

fn directory(path: &Path) -> io::Result<Option<File>> {
    match OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC)
        .open(path)
    {
        Ok(file) => Ok(Some(file)),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e),
    }
}

fn child(parent: &File, name: &str) -> io::Result<Option<File>> {
    let name = CString::new(name)?;
    // The parent is a pinned directory and the child cannot be a symlink.
    let fd = unsafe {
        libc::openat(
            parent.as_raw_fd(),
            name.as_ptr(),
            libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
        )
    };
    if fd >= 0 {
        Ok(Some(unsafe { File::from_raw_fd(fd) }))
    } else {
        let e = io::Error::last_os_error();
        if e.kind() == io::ErrorKind::NotFound {
            Ok(None)
        } else {
            Err(e)
        }
    }
}

fn entry(dir: &File, name: &str) -> io::Result<Option<libc::stat>> {
    let name = CString::new(name)?;
    let mut stat = std::mem::MaybeUninit::uninit();
    let result = unsafe {
        libc::fstatat(
            dir.as_raw_fd(),
            name.as_ptr(),
            stat.as_mut_ptr(),
            libc::AT_SYMLINK_NOFOLLOW,
        )
    };
    if result == 0 {
        Ok(Some(unsafe { stat.assume_init() }))
    } else {
        let e = io::Error::last_os_error();
        if e.kind() == io::ErrorKind::NotFound {
            Ok(None)
        } else {
            Err(e)
        }
    }
}

fn owned(dir: &File) -> io::Result<bool> {
    Ok(entry(dir, OWNER)?.is_some_and(|s| s.st_mode & libc::S_IFMT == libc::S_IFREG))
}

fn enabled(dir: &File) -> io::Result<bool> {
    Ok(entry(dir, "disable")?.is_none() && entry(dir, "remove")?.is_none())
}

fn selected(live: &File, staged: Option<&File>) -> io::Result<Option<&'static str>> {
    let mut selected = None;
    for id in PROVIDERS {
        let current = child(live, id)?;
        let update = staged.map(|root| child(root, id)).transpose()?.flatten();
        if let Some(current) = current
            && owned(&current)?
            && enabled(&current)?
        {
            if let Some(update) = update {
                // Never adopt a foreign staged copy of an owned ID.
                if !owned(&update)? {
                    return Err(io::Error::other(
                        "foreign update occupies a built-in Zygisk ID",
                    ));
                }
                if !enabled(&update)? {
                    continue;
                }
            }
            if selected.is_none() {
                selected = Some(id);
            }
        }
    }
    Ok(selected)
}

fn disable(dir: &File) -> io::Result<()> {
    if let Some(stat) = entry(dir, "disable")? {
        return if stat.st_mode & libc::S_IFMT == libc::S_IFREG {
            Ok(())
        } else {
            Err(io::Error::other("unsafe Zygisk disable marker"))
        };
    }
    // Exclusive creation neither follows nor truncates an existing marker.
    let fd = unsafe {
        libc::openat(
            dir.as_raw_fd(),
            c"disable".as_ptr(),
            libc::O_WRONLY
                | libc::O_CREAT
                | libc::O_EXCL
                | libc::O_NOFOLLOW
                | libc::O_CLOEXEC
                | libc::O_NONBLOCK,
            0o600,
        )
    };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let marker = unsafe { File::from_raw_fd(fd) };
    marker.sync_all()?;
    dir.sync_all()
}

/// Called after staged module promotion and before module policy/startup scripts.
/// Marks persist; switching the built-in off never re-enables an external module.
pub fn reconcile(live: &Path, staged: &Path) -> io::Result<()> {
    let Some(live) = directory(live)? else {
        return Ok(());
    };
    let staged = directory(staged)?;
    let Some(selected) = selected(&live, staged.as_ref())? else {
        return Ok(());
    };
    for id in PROVIDERS.into_iter().filter(|id| *id != selected) {
        for root in [Some(&live), staged.as_ref()].into_iter().flatten() {
            if let Some(dir) = child(root, id)? {
                disable(&dir)?;
                log::info!("built-in Zygisk {selected}: disabled conflicting provider {id}");
            }
        }
    }
    Ok(())
}

/// Also gate every later module stage, even if a disable-marker write failed.
/// An ambiguous/unsafe provider tree fails closed for injectors, not unrelated modules.
pub fn active_allowed(live: &Path, staged: &Path, id: &str) -> io::Result<bool> {
    if !PROVIDERS.contains(&id) && id != ADDON {
        return Ok(true);
    }
    let Some(live) = directory(live)? else {
        return Ok(true);
    };
    if let Some(current) = child(&live, id)?
        && !enabled(&current)?
    {
        // A dangling marker is still a marker, even though Path::exists follows it.
        return Ok(false);
    }
    let staged = directory(staged)?;
    Ok(selected(&live, staged.as_ref())?.is_none_or(|chosen| id == chosen || id == ADDON))
}

/// Filter the *next boot snapshot*, without disabling modules in the current boot.
/// This covers module init.rc/preinit hooks that run earlier than post-fs-data.
pub fn filter_next_boot(modules: &mut BTreeMap<String, PathBuf>) -> io::Result<()> {
    let choose = || -> io::Result<(Option<&'static str>, Vec<&'static str>)> {
        let mut chosen = None;
        let mut inactive = Vec::new();
        for id in PROVIDERS {
            if let Some(path) = modules.get(id) {
                let dir =
                    directory(path)?.ok_or_else(|| io::Error::other("missing Zygisk snapshot"))?;
                if !enabled(&dir)? {
                    inactive.push(id);
                    continue;
                }
                if owned(&dir)? && chosen.is_none() {
                    chosen = Some(id);
                }
            }
        }
        Ok((chosen, inactive))
    };
    match choose() {
        Ok((chosen, inactive)) => modules.retain(|id, _| {
            !inactive.contains(&id.as_str())
                && chosen.is_none_or(|chosen| !PROVIDERS.contains(&id.as_str()) || id == chosen)
        }),
        Err(e) => {
            modules.retain(|id, _| !PROVIDERS.contains(&id.as_str()) && id != ADDON);
            return Err(e);
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        fs,
        os::unix::fs::{MetadataExt, symlink},
    };
    use tempfile::{TempDir, tempdir};

    struct Tree {
        _temp: TempDir,
        live: PathBuf,
        staged: PathBuf,
    }

    impl Tree {
        fn new() -> Self {
            let temp = tempdir().unwrap();
            let live = temp.path().join("modules");
            let staged = temp.path().join("modules_update");
            fs::create_dir(&live).unwrap();
            fs::create_dir(&staged).unwrap();
            Self {
                _temp: temp,
                live,
                staged,
            }
        }

        fn module(&self, root: &Path, id: &str, builtin: bool) -> PathBuf {
            let path = root.join(id);
            fs::create_dir_all(&path).unwrap();
            fs::write(path.join("module.prop"), format!("id={id}\n")).unwrap();
            if builtin {
                fs::write(path.join(OWNER), "").unwrap();
            }
            path
        }

        fn reconcile(&self) -> io::Result<()> {
            reconcile(&self.live, &self.staged)
        }

        fn allowed(&self, id: &str) -> io::Result<bool> {
            active_allowed(&self.live, &self.staged, id)
        }
    }

    #[test]
    fn external_only_is_never_disabled_or_adopted() {
        let tree = Tree::new();
        for id in PROVIDERS {
            tree.module(&tree.live, id, false);
        }
        tree.reconcile().unwrap();
        for id in PROVIDERS {
            assert!(!tree.live.join(id).join("disable").exists());
            assert!(!tree.live.join(id).join(OWNER).exists());
            assert!(tree.allowed(id).unwrap());
        }
    }

    #[test]
    fn disabled_or_removing_builtin_does_not_change_external_modules() {
        for marker in ["disable", "remove"] {
            let tree = Tree::new();
            let builtin = tree.module(&tree.live, "rezygisk", true);
            fs::write(builtin.join(marker), "").unwrap();
            let foreign = tree.module(&tree.live, "zygisksu", false);
            tree.reconcile().unwrap();
            assert!(!foreign.join("disable").exists());
            assert!(tree.allowed("zygisksu").unwrap());
        }
    }

    #[test]
    fn non_regular_control_markers_cannot_start_a_disabled_builtin() {
        for marker in ["disable", "remove"] {
            for fifo in [false, true] {
                let tree = Tree::new();
                let builtin = tree.module(&tree.live, "rezygisk", true);
                let foreign = tree.module(&tree.live, "zygisksu", false);
                let path = builtin.join(marker);
                if fifo {
                    let name = CString::new(path.as_os_str().as_encoded_bytes()).unwrap();
                    assert_eq!(unsafe { libc::mkfifo(name.as_ptr(), 0o600) }, 0);
                } else {
                    symlink(tree._temp.path().join("missing"), path).unwrap();
                }
                tree.reconcile().unwrap();
                assert!(!tree.allowed("rezygisk").unwrap());
                assert!(tree.allowed("zygisksu").unwrap());
                assert!(!foreign.join("disable").exists());
                let mut snapshot =
                    BTreeMap::from([("rezygisk".into(), builtin), ("zygisksu".into(), foreign)]);
                filter_next_boot(&mut snapshot).unwrap();
                assert_eq!(snapshot.len(), 1);
                assert!(snapshot.contains_key("zygisksu"));
            }
        }
    }

    #[test]
    fn only_other_known_provider_copies_are_disabled() {
        for (builtin_id, foreign_id) in [("rezygisk", "zygisksu"), ("zygisksu", "rezygisk")] {
            let tree = Tree::new();
            let builtin = tree.module(&tree.live, builtin_id, true);
            let foreign = tree.module(&tree.live, foreign_id, false);
            let staged = tree.module(&tree.staged, foreign_id, false);
            for id in ["lsposed", "sui", "some_plugin", ADDON, "zygisknext"] {
                let path = tree.module(&tree.live, id, false);
                fs::create_dir(path.join("zygisk")).unwrap();
            }
            tree.reconcile().unwrap();
            tree.reconcile().unwrap();
            assert!(foreign.join("disable").is_file());
            assert!(staged.join("disable").is_file());
            assert!(!foreign.join(OWNER).exists());
            assert!(!foreign.join("remove").exists());
            assert!(!builtin.join("disable").exists());
            assert!(!tree.allowed(foreign_id).unwrap());
            assert!(tree.allowed(builtin_id).unwrap());
            for id in ["lsposed", "sui", "some_plugin", ADDON, "zygisknext"] {
                assert!(!tree.live.join(id).join("disable").exists());
                assert!(tree.allowed(id).unwrap());
            }
        }
    }

    #[test]
    fn staged_only_builtin_changes_snapshot_not_current_boot() {
        let tree = Tree::new();
        let builtin = tree.module(&tree.staged, "rezygisk", true);
        let foreign = tree.module(&tree.live, "zygisksu", false);
        tree.reconcile().unwrap();
        assert!(!foreign.join("disable").exists());
        assert!(tree.allowed("zygisksu").unwrap());
        let mut snapshot = BTreeMap::from([
            ("rezygisk".into(), builtin),
            ("zygisksu".into(), foreign.clone()),
            ("sui".into(), tree.module(&tree.live, "sui", false)),
        ]);
        filter_next_boot(&mut snapshot).unwrap();
        assert!(snapshot.contains_key("rezygisk"));
        assert!(!snapshot.contains_key("zygisksu"));
        assert!(snapshot.contains_key("sui"));
        assert!(!foreign.join("disable").exists());
    }

    #[test]
    fn promoted_builtin_enforces_the_boot_policy() {
        let tree = Tree::new();
        let staged = tree.module(&tree.staged, "rezygisk", true);
        let foreign = tree.module(&tree.live, "zygisksu", false);
        fs::rename(staged, tree.live.join("rezygisk")).unwrap();
        tree.reconcile().unwrap();
        assert!(foreign.join("disable").exists());
        assert!(!tree.allowed("zygisksu").unwrap());
    }

    #[test]
    fn foreign_same_id_update_is_not_adopted_or_written() {
        let tree = Tree::new();
        tree.module(&tree.live, "rezygisk", true);
        let foreign = tree.module(&tree.staged, "rezygisk", false);
        tree.module(&tree.live, "zygisksu", false);
        assert!(tree.reconcile().is_err());
        assert!(!foreign.join(OWNER).exists());
        assert!(!foreign.join("disable").exists());
        assert!(tree.allowed("rezygisk").is_err());
        assert!(tree.allowed("zygisksu").is_err());
        assert!(tree.allowed("lsposed").unwrap());
    }

    #[test]
    fn disabled_staged_copy_turns_off_builtin_selection() {
        let tree = Tree::new();
        tree.module(&tree.live, "rezygisk", true);
        let staged = tree.module(&tree.staged, "rezygisk", true);
        fs::write(staged.join("disable"), "").unwrap();
        let foreign = tree.module(&tree.live, "zygisksu", false);
        tree.reconcile().unwrap();
        assert!(!foreign.join("disable").exists());
        assert!(tree.allowed("zygisksu").unwrap());
    }

    #[test]
    fn disabled_marks_persist_and_reenabled_external_is_suppressed_again() {
        let tree = Tree::new();
        let builtin = tree.module(&tree.live, "rezygisk", true);
        let foreign = tree.module(&tree.live, "zygisksu", false);
        tree.reconcile().unwrap();
        fs::remove_file(foreign.join("disable")).unwrap();
        assert!(!tree.allowed("zygisksu").unwrap());
        tree.reconcile().unwrap();
        fs::write(builtin.join("disable"), "").unwrap();
        tree.reconcile().unwrap();
        assert!(foreign.join("disable").exists());
    }

    #[test]
    fn two_owned_backends_never_both_start() {
        let tree = Tree::new();
        for id in PROVIDERS {
            tree.module(&tree.live, id, true);
        }
        tree.reconcile().unwrap();
        assert!(tree.allowed("rezygisk").unwrap());
        assert!(!tree.allowed("zygisksu").unwrap());
        assert!(tree.live.join("zygisksu/disable").exists());
    }

    #[test]
    fn symlinked_module_or_root_is_never_followed() {
        let tree = Tree::new();
        tree.module(&tree.live, "rezygisk", true);
        let outside = tree.module(tree._temp.path(), "outside", false);
        symlink(&outside, tree.live.join("zygisksu")).unwrap();
        assert!(tree.reconcile().is_err());
        assert!(tree.allowed("rezygisk").is_err());
        assert!(!outside.join("disable").exists());
        fs::remove_dir(&tree.staged).unwrap();
        symlink(&outside, &tree.staged).unwrap();
        assert!(tree.reconcile().is_err());
        assert!(tree.allowed("sui").unwrap());
    }

    #[test]
    fn owner_symlink_is_not_an_ownership_claim() {
        let tree = Tree::new();
        let foreign = tree.module(&tree.live, "rezygisk", false);
        fs::write(tree._temp.path().join("owner"), "").unwrap();
        symlink(tree._temp.path().join("owner"), foreign.join(OWNER)).unwrap();
        let other = tree.module(&tree.live, "zygisksu", false);
        tree.reconcile().unwrap();
        assert!(!other.join("disable").exists());
    }

    #[test]
    fn symlink_or_fifo_disable_marker_cannot_redirect_or_block_writes() {
        for fifo in [false, true] {
            let tree = Tree::new();
            tree.module(&tree.live, "rezygisk", true);
            let foreign = tree.module(&tree.live, "zygisksu", false);
            let sentinel = tree._temp.path().join("sentinel");
            fs::write(&sentinel, "unchanged").unwrap();
            if fifo {
                let path =
                    CString::new(foreign.join("disable").as_os_str().as_encoded_bytes()).unwrap();
                assert_eq!(unsafe { libc::mkfifo(path.as_ptr(), 0o600) }, 0);
            } else {
                symlink(&sentinel, foreign.join("disable")).unwrap();
            }
            assert!(tree.reconcile().is_err());
            assert_eq!(fs::read_to_string(sentinel).unwrap(), "unchanged");
            assert!(!tree.allowed("zygisksu").unwrap());
        }
    }

    #[test]
    fn existing_hardlinked_marker_is_never_truncated() {
        let tree = Tree::new();
        tree.module(&tree.live, "rezygisk", true);
        let foreign = tree.module(&tree.live, "zygisksu", false);
        let sentinel = tree._temp.path().join("sentinel");
        fs::write(&sentinel, "keep").unwrap();
        fs::hard_link(&sentinel, foreign.join("disable")).unwrap();
        let inode = fs::metadata(&sentinel).unwrap().ino();
        tree.reconcile().unwrap();
        assert_eq!(fs::read_to_string(&sentinel).unwrap(), "keep");
        assert_eq!(fs::metadata(foreign.join("disable")).unwrap().ino(), inode);
    }

    #[test]
    fn unsafe_snapshot_removes_only_injectors_and_their_loader() {
        let tree = Tree::new();
        let builtin = tree.module(&tree.live, "rezygisk", true);
        symlink(&builtin, tree.live.join("zygisksu")).unwrap();
        let mut snapshot = BTreeMap::from([
            ("rezygisk".into(), builtin),
            ("zygisksu".into(), tree.live.join("zygisksu")),
            (ADDON.into(), tree.module(&tree.live, ADDON, true)),
            ("sui".into(), tree.module(&tree.live, "sui", false)),
        ]);
        assert!(filter_next_boot(&mut snapshot).is_err());
        assert_eq!(
            snapshot.keys().map(String::as_str).collect::<Vec<_>>(),
            ["sui"]
        );
    }

    #[test]
    fn missing_roots_and_external_only_snapshot_are_noops() {
        let tree = Tree::new();
        let a = tree.module(&tree.live, "rezygisk", false);
        let b = tree.module(&tree.live, "zygisksu", false);
        let mut snapshot = BTreeMap::from([("rezygisk".into(), a), ("zygisksu".into(), b)]);
        let before = snapshot.clone();
        filter_next_boot(&mut snapshot).unwrap();
        assert_eq!(snapshot, before);
        reconcile(&tree.live.join("absent"), &tree.staged).unwrap();
        assert!(active_allowed(&tree.live.join("absent"), &tree.staged, "rezygisk").unwrap());
    }
}
