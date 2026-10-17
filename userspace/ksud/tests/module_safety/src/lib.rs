#![cfg(target_os = "linux")]
#![allow(dead_code)]

// Compile the production implementations with isolated host paths only.
mod defs {
    pub const MODULE_CONFIG_DIR: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/fixtures/configs");
    pub const PERSIST_CONFIG_NAME: &str = "persist.config";
    pub const TEMP_CONFIG_NAME: &str = "tmp.config";
}

mod utils {
    pub fn ensure_dir_exists(path: impl AsRef<std::path::Path>) -> anyhow::Result<()> {
        std::fs::create_dir_all(path)?;
        Ok(())
    }
}

mod module {
    pub fn validate_module_id(id: &str) -> anyhow::Result<()> {
        anyhow::ensure!(
            regex_lite::Regex::new(r"^[a-zA-Z][a-zA-Z0-9._-]+$")?.is_match(id),
            "Invalid module ID"
        );
        Ok(())
    }
}

#[path = "../../../src/module/file_lock.rs"]
mod file_lock;
#[path = "../../../src/hot_install/script.rs"]
mod hot_install;
#[path = "../../../src/module_config.rs"]
mod module_config;
#[path = "../../../src/module/transaction.rs"]
mod transaction;

#[cfg(test)]
mod tests {
    use super::*;
    use module_config::{ConfigType, load_config, set_config_value};
    use std::{
        collections::HashMap,
        fs,
        os::unix::{
            fs::{MetadataExt, PermissionsExt},
            process::ExitStatusExt,
        },
        path::{Path, PathBuf},
        process::{Child, Command},
        thread,
        time::{Duration, Instant},
    };

    fn fixture() -> tempfile::TempDir {
        let root = std::env::var_os("KSUD_MODULE_FIXTURE_ROOT").map_or_else(
            || Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures"),
            PathBuf::from,
        );
        fs::create_dir_all(&root).unwrap();
        tempfile::Builder::new()
            .prefix("fixture-")
            .tempdir_in(root)
            .unwrap()
    }

    fn wait_file(path: &Path) {
        let deadline = Instant::now() + Duration::from_secs(10);
        while !path.exists() {
            assert!(
                Instant::now() < deadline,
                "Timed out waiting for {}",
                path.display()
            );
            thread::sleep(Duration::from_millis(5));
        }
    }

    struct Worker(Child);

    impl std::ops::Deref for Worker {
        type Target = Child;
        fn deref(&self) -> &Child {
            &self.0
        }
    }

    impl std::ops::DerefMut for Worker {
        fn deref_mut(&mut self) -> &mut Child {
            &mut self.0
        }
    }

    impl Drop for Worker {
        fn drop(&mut self) {
            self.0.kill().ok();
            self.0.wait().ok();
        }
    }

    fn worker(kind: &str, root: &Path, id: &str) -> Worker {
        worker_with(kind, root, id, &[])
    }

    fn worker_with(kind: &str, root: &Path, id: &str, envs: &[(&str, &str)]) -> Worker {
        Worker(
            Command::new(std::env::current_exe().unwrap())
                .args([
                    "--ignored",
                    "--exact",
                    "tests::worker_process",
                    "--nocapture",
                ])
                .env("MODULE_SAFETY_WORKER", kind)
                .env("MODULE_SAFETY_FIXTURE", root)
                .env("MODULE_SAFETY_ID", id)
                .envs(envs.iter().copied())
                .spawn()
                .unwrap(),
        )
    }

    #[test]
    #[ignore = "invoked in a separate process by the tests"]
    fn worker_process() {
        let kind = std::env::var("MODULE_SAFETY_WORKER").unwrap();
        let root = PathBuf::from(std::env::var_os("MODULE_SAFETY_FIXTURE").unwrap());
        let id = std::env::var("MODULE_SAFETY_ID").unwrap();
        match kind.as_str() {
            "alpha" | "beta" => {
                fs::write(root.join(format!("ready-{kind}")), "ready").unwrap();
                wait_file(&root.join("start"));
                for version in 0..30 {
                    let value = format!("{version}:{}", kind.repeat(16 * 1024));
                    set_config_value(&id, &kind, &value, ConfigType::Persist).unwrap();
                }
            }
            "interrupted-install" => {
                let store = transaction::Store::open(
                    &root.join("transactions"),
                    &root.join("modules_update"),
                    &root.join("modules"),
                )
                .unwrap();
                let install = store.install(&id).unwrap();
                fs::write(install.module().join("partial"), "not committed").unwrap();
                fs::write(root.join("ready"), "ready").unwrap();
                loop {
                    thread::park();
                }
            }
            "config-lock" => {
                fs::create_dir_all(defs::MODULE_CONFIG_DIR).unwrap();
                let _lock = file_lock::FileLock::exclusive(
                    &Path::new(defs::MODULE_CONFIG_DIR).join(".config.lock"),
                )
                .unwrap();
                fs::write(root.join("ready"), "ready").unwrap();
                loop {
                    thread::park();
                }
            }
            "clear-module" => {
                fs::write(root.join("ready"), "ready").unwrap();
                module_config::clear_module_configs(&id).unwrap();
            }
            "publication" | "publication-drop-failure" => {
                let updates = root.join("modules_update");
                let live = root.join("modules");
                let store =
                    transaction::Store::open(&root.join("transactions"), &updates, &live).unwrap();
                let install = store.install(&id).unwrap();
                fs::write(install.module().join("version"), "candidate").unwrap();
                fs::write(
                    install.module().join("module.prop"),
                    "id=example\nversion=candidate\n",
                )
                .unwrap();
                let journal = install.module().parent().unwrap().parent().unwrap();
                fs::write(root.join("journal-path"), journal.to_str().unwrap()).unwrap();
                let publication = install.publish_guard(&updates.join(&id)).unwrap();
                let live = live.join(&id);
                publication
                    .capture_metadata(&live, &["module.prop", "update", "disable", "remove"])
                    .unwrap();
                fs::write(live.join("module.prop"), "id=example\nversion=candidate\n").unwrap();
                fs::write(live.join("update"), "").unwrap();
                fs::remove_file(live.join("disable")).unwrap();
                fs::remove_file(live.join("remove")).unwrap();
                if kind == "publication-drop-failure" {
                    drop(publication);
                    drop(install);
                    fs::write(root.join("paused"), "drop-failed").unwrap();
                } else if std::env::var("MODULE_SAFETY_PAUSE_AT").as_deref()
                    == Ok("publication-metadata")
                {
                    fs::write(root.join("paused"), "publication-metadata").unwrap();
                } else if std::env::var("MODULE_SAFETY_PAUSE_AT").as_deref()
                    == Ok("publication-rollback")
                {
                    drop(publication);
                } else {
                    publication.commit().unwrap();
                    fs::write(root.join("paused"), "publication-complete").unwrap();
                }
                loop {
                    thread::park();
                }
            }
            _ => panic!("Unknown worker"),
        }
    }

    #[test]
    fn two_process_writers_keep_different_keys_and_readers_see_complete_files() {
        let temp = fixture();
        let id = format!(
            "writers_{}",
            temp.path().file_name().unwrap().to_str().unwrap()
        );
        let mut first = worker("alpha", temp.path(), &id);
        let mut second = worker("beta", temp.path(), &id);
        wait_file(&temp.path().join("ready-alpha"));
        wait_file(&temp.path().join("ready-beta"));
        fs::write(temp.path().join("start"), "start").unwrap();
        let deadline = Instant::now() + Duration::from_secs(30);
        loop {
            let config = load_config(&id, ConfigType::Persist).unwrap();
            for (key, value) in config {
                assert!(key == "alpha" || key == "beta");
                let (version, payload) = value.split_once(':').unwrap();
                assert!(version.parse::<usize>().unwrap() < 30);
                assert_eq!(payload, key.repeat(16 * 1024));
            }
            let a = first.try_wait().unwrap();
            let b = second.try_wait().unwrap();
            if let (Some(a), Some(b)) = (a, b) {
                assert!(a.success() && b.success());
                break;
            }
            assert!(Instant::now() < deadline);
        }
        let config = load_config(&id, ConfigType::Persist).unwrap();
        assert_eq!(config.len(), 2);
        for key in ["alpha", "beta"] {
            assert_eq!(config[key], format!("29:{}", key.repeat(16 * 1024)));
        }
        module_config::clear_module_configs(&id).unwrap();
    }

    #[test]
    fn killed_config_writer_releases_lock_and_private_writes_preserve_permissions() {
        let temp = fixture();
        let id = format!(
            "locked_{}",
            temp.path().file_name().unwrap().to_str().unwrap()
        );
        let mut child = worker("config-lock", temp.path(), &id);
        wait_file(&temp.path().join("ready"));
        child.kill().unwrap();
        child.wait().unwrap();
        set_config_value(&id, "key", "old", ConfigType::Persist).unwrap();
        let path = Path::new(defs::MODULE_CONFIG_DIR)
            .join(&id)
            .join(defs::PERSIST_CONFIG_NAME);
        fs::set_permissions(&path, fs::Permissions::from_mode(0o640)).unwrap();
        let original_mode = fs::metadata(&path).unwrap().permissions().mode();
        let predictable_tmp = path.with_extension("tmp");
        fs::write(&predictable_tmp, "do not touch").unwrap();
        set_config_value(&id, "other", "new", ConfigType::Persist).unwrap();
        assert_eq!(
            fs::metadata(&path).unwrap().permissions().mode(),
            original_mode
        );
        assert_eq!(fs::read_to_string(predictable_tmp).unwrap(), "do not touch");
        let invalid: HashMap<_, _> = (0..33)
            .map(|i| (format!("key{i}"), "value".into()))
            .collect();
        assert!(module_config::save_config(&id, ConfigType::Persist, &invalid).is_err());
        assert_eq!(load_config(&id, ConfigType::Persist).unwrap().len(), 2);
        assert!(!fs::read_dir(path.parent().unwrap()).unwrap().any(|e| {
            e.unwrap()
                .file_name()
                .to_string_lossy()
                .starts_with(".config-write-")
        }));
        module_config::clear_module_configs(&id).unwrap();
    }

    #[test]
    fn uninstall_writer_uses_the_same_stable_lock_inode() {
        let temp = fixture();
        let id = format!(
            "uninstall_{}",
            temp.path().file_name().unwrap().to_str().unwrap()
        );
        set_config_value(&id, "key", "value", ConfigType::Persist).unwrap();
        let path = Path::new(defs::MODULE_CONFIG_DIR).join(".config.lock");
        let lock = file_lock::FileLock::exclusive(&path).unwrap();
        let inode = fs::metadata(&path).unwrap().ino();
        let mut child = worker("clear-module", temp.path(), &id);
        wait_file(&temp.path().join("ready"));
        thread::sleep(Duration::from_millis(100));
        assert!(child.try_wait().unwrap().is_none());
        drop(lock);
        assert!(child.wait().unwrap().success());
        assert_eq!(fs::metadata(path).unwrap().ino(), inode);
        assert!(!Path::new(defs::MODULE_CONFIG_DIR).join(id).exists());
    }

    #[test]
    fn config_merge_delete_and_temp_cleanup_keep_the_existing_api() {
        let temp = fixture();
        let id = format!("api_{}", temp.path().file_name().unwrap().to_str().unwrap());
        set_config_value(&id, "shared", "persist", ConfigType::Persist).unwrap();
        set_config_value(&id, "shared", "temp", ConfigType::Temp).unwrap();
        assert_eq!(module_config::merge_configs(&id).unwrap()["shared"], "temp");
        module_config::clear_all_temp_configs().unwrap();
        assert_eq!(
            module_config::merge_configs(&id).unwrap()["shared"],
            "persist"
        );
        module_config::delete_config_value(&id, "shared", ConfigType::Persist).unwrap();
        assert!(load_config(&id, ConfigType::Persist).unwrap().is_empty());
        assert!(module_config::clear_config("../outside", ConfigType::Persist).is_err());
        module_config::clear_config(&id, ConfigType::Persist).unwrap();
        module_config::clear_module_configs(&id).unwrap();
    }

    fn roots(root: &Path) -> (PathBuf, PathBuf, PathBuf) {
        let transactions = root.join("transactions");
        let updates = root.join("modules_update");
        let modules = root.join("modules");
        fs::create_dir_all(&updates).unwrap();
        fs::create_dir_all(&modules).unwrap();
        (transactions, updates, modules)
    }

    fn exchange_supported(root: &Path) -> bool {
        use std::{ffi::CString, os::unix::ffi::OsStrExt};
        let temp = tempfile::tempdir_in(root).unwrap();
        let first = temp.path().join("first");
        let second = temp.path().join("second");
        fs::create_dir(&first).unwrap();
        fs::create_dir(&second).unwrap();
        let first = CString::new(first.as_os_str().as_bytes()).unwrap();
        let second = CString::new(second.as_os_str().as_bytes()).unwrap();
        let result = unsafe {
            libc::syscall(
                libc::SYS_renameat2,
                libc::AT_FDCWD,
                first.as_ptr(),
                libc::AT_FDCWD,
                second.as_ptr(),
                2,
            )
        };
        if result == 0 {
            true
        } else {
            let error = std::io::Error::last_os_error();
            assert!(
                matches!(
                    error.raw_os_error(),
                    Some(libc::EINVAL | libc::ENOSYS | libc::EOPNOTSUPP)
                ),
                "{error}"
            );
            eprintln!(
                "SKIP exchange-success assertions: fixture filesystem lacks RENAME_EXCHANGE ({error})"
            );
            false
        }
    }

    #[test]
    fn interrupted_install_is_nonpromotable_and_recovery_cleans_it() {
        let temp = fixture();
        let (transactions, updates, modules) = roots(temp.path());
        let live = modules.join("example");
        fs::create_dir(&live).unwrap();
        fs::write(live.join("version"), "old").unwrap();
        let mut child = worker("interrupted-install", temp.path(), "example");
        wait_file(&temp.path().join("ready"));
        assert_eq!(fs::read_dir(&updates).unwrap().count(), 0);
        child.kill().unwrap();
        child.wait().unwrap();
        let _store = transaction::Store::open(&transactions, &updates, &modules).unwrap();
        assert_eq!(fs::read_dir(&transactions).unwrap().count(), 1); // stable lock only
        assert_eq!(fs::read_to_string(live.join("version")).unwrap(), "old");
        assert!(!updates.join("example").exists());
    }

    #[test]
    fn successful_publication_and_boot_promotion_preserve_lifecycle_flags() {
        for flag in ["disable", "remove"] {
            let temp = fixture();
            let (transactions, updates, modules) = roots(temp.path());
            if !exchange_supported(temp.path()) {
                return;
            }
            let live = modules.join("example");
            let update = updates.join("example");
            fs::create_dir(&live).unwrap();
            fs::write(live.join("version"), "old").unwrap();
            fs::write(live.join(flag), "").unwrap();
            let store = transaction::Store::open(&transactions, &updates, &modules).unwrap();
            let install = store.install("example").unwrap();
            fs::write(install.module().join("version"), "new").unwrap();
            install.publish(&update).unwrap();
            assert_eq!(fs::read_to_string(live.join("version")).unwrap(), "old");
            store
                .promote(&update, &live, &["remove", "disable"])
                .unwrap();
            assert_eq!(fs::read_to_string(live.join("version")).unwrap(), "new");
            assert!(live.join(flag).exists());
            assert!(!update.exists());
        }
    }

    #[test]
    fn failed_attempt_drops_private_payload_but_preserves_previous_pending_update() {
        let temp = fixture();
        let (transactions, updates, modules) = roots(temp.path());
        let update = updates.join("example");
        fs::create_dir(&update).unwrap();
        fs::write(update.join("version"), "previous").unwrap();
        let store = transaction::Store::open(&transactions, &updates, &modules).unwrap();
        let private;
        {
            let install = store.install("example").unwrap();
            private = install.module().to_owned();
            fs::write(&private.join("partial"), "failed extraction or installer").unwrap();
            // Same early-return/drop path as extraction and installer errors.
        }
        assert!(!private.exists());
        assert_eq!(
            fs::read_to_string(update.join("version")).unwrap(),
            "previous"
        );
        let successful = store.install("example").unwrap();
        fs::write(successful.module().join("version"), "new").unwrap();
        if exchange_supported(temp.path()) {
            successful.publish(&update).unwrap();
            assert_eq!(fs::read_to_string(update.join("version")).unwrap(), "new");
        } else {
            assert!(successful.publish(&update).is_err());
            assert_eq!(
                fs::read_to_string(update.join("version")).unwrap(),
                "previous"
            );
        }
    }

    #[test]
    fn hot_script_absolute_symlink_is_resolved_before_private_publication() {
        let temp = fixture();
        let (transactions, updates, modules) = roots(temp.path());
        let store = transaction::Store::open(&transactions, &updates, &modules).unwrap();
        let install = store.install("example").unwrap();
        fs::create_dir(install.module().join("scripts")).unwrap();
        fs::write(install.module().join("scripts/run.sh"), "exit 0").unwrap();
        std::os::unix::fs::symlink(
            install.module().join("scripts/run.sh"),
            install.module().join("action.sh"),
        )
        .unwrap();
        fs::write(install.module().join(".ksu_hot_install"), "action.sh").unwrap();
        install.prepare_hot_request(".ksu_hot_install").unwrap();
        let update = updates.join("example");
        install.publish(&update).unwrap();
        assert_eq!(
            fs::read_to_string(update.join(".ksu_hot_install")).unwrap(),
            "scripts/run.sh"
        );
        assert!(update.join("scripts/run.sh").is_file());
    }

    #[test]
    fn late_install_error_withdraws_new_update_and_restores_previous_pending_update() {
        for previous in [false, true] {
            let temp = fixture();
            let (transactions, updates, modules) = roots(temp.path());
            if previous && !exchange_supported(temp.path()) {
                continue;
            }
            let update = updates.join("example");
            if previous {
                fs::create_dir(&update).unwrap();
                fs::write(update.join("version"), "previous").unwrap();
            }
            let store = transaction::Store::open(&transactions, &updates, &modules).unwrap();
            let install = store.install("example").unwrap();
            fs::write(install.module().join("version"), "uncommitted").unwrap();
            {
                let _publication = install.publish_guard(&update).unwrap();
                // A metadata/symlink error returns before commit.
            }
            assert_eq!(update.exists(), previous);
            if previous {
                assert_eq!(
                    fs::read_to_string(update.join("version")).unwrap(),
                    "previous"
                );
            }
        }
    }

    #[test]
    fn late_install_error_restores_live_metadata_and_lifecycle_flags() {
        let temp = fixture();
        let (transactions, updates, modules) = roots(temp.path());
        let live = modules.join("example");
        fs::create_dir(&live).unwrap();
        fs::write(live.join("module.prop"), "id=example\nversion=old\n").unwrap();
        fs::write(live.join("disable"), "").unwrap();
        fs::write(live.join("remove"), "").unwrap();
        let store = transaction::Store::open(&transactions, &updates, &modules).unwrap();
        let install = store.install("example").unwrap();
        {
            let publication = install.publish_guard(&updates.join("example")).unwrap();
            publication
                .capture_metadata(&live, &["module.prop", "update", "disable", "remove"])
                .unwrap();
            fs::write(live.join("module.prop"), "id=example\nversion=new\n").unwrap();
            fs::write(live.join("update"), "").unwrap();
            fs::remove_file(live.join("disable")).unwrap();
            fs::remove_file(live.join("remove")).unwrap();
        }
        assert_eq!(
            fs::read_to_string(live.join("module.prop")).unwrap(),
            "id=example\nversion=old\n"
        );
        assert!(live.join("disable").exists());
        assert!(live.join("remove").exists());
        assert!(!live.join("update").exists());
    }

    fn publication_fixture(previous: bool) -> tempfile::TempDir {
        let temp = fixture();
        assert!(
            exchange_supported(temp.path()),
            "Process-death tests require KSUD_MODULE_FIXTURE_ROOT on native POSIX, not DrvFS"
        );
        let (_, updates, modules) = roots(temp.path());
        let live = modules.join("example");
        fs::create_dir(&live).unwrap();
        fs::write(live.join("version"), "working").unwrap();
        fs::write(live.join("module.prop"), "id=example\nversion=working\n").unwrap();
        fs::set_permissions(live.join("module.prop"), fs::Permissions::from_mode(0o640)).unwrap();
        fs::write(live.join("disable"), "").unwrap();
        fs::write(live.join("remove"), "").unwrap();
        fs::write(
            temp.path().join("working-inode"),
            fs::metadata(&live).unwrap().ino().to_string(),
        )
        .unwrap();
        if previous {
            fs::create_dir(updates.join("example")).unwrap();
            fs::write(updates.join("example/version"), "previous-pending").unwrap();
            fs::write(
                temp.path().join("pending-inode"),
                fs::metadata(updates.join("example"))
                    .unwrap()
                    .ino()
                    .to_string(),
            )
            .unwrap();
        }
        temp
    }

    fn assert_publication_recovered(root: &Path, previous: bool, committed: bool) {
        let live = root.join("modules/example");
        let update = root.join("modules_update/example");
        assert_eq!(fs::read_to_string(live.join("version")).unwrap(), "working");
        assert_eq!(
            fs::metadata(&live).unwrap().ino().to_string(),
            fs::read_to_string(root.join("working-inode")).unwrap()
        );
        assert_eq!(update.exists(), previous || committed);
        if previous || committed {
            assert_eq!(
                fs::read_to_string(update.join("version")).unwrap(),
                if committed {
                    "candidate"
                } else {
                    "previous-pending"
                }
            );
            assert!(!transaction::is_pending_publication(&update));
            if !committed {
                assert_eq!(
                    fs::metadata(&update).unwrap().ino().to_string(),
                    fs::read_to_string(root.join("pending-inode")).unwrap()
                );
            }
        }
        assert_eq!(
            fs::read_to_string(live.join("module.prop")).unwrap(),
            if committed {
                "id=example\nversion=candidate\n"
            } else {
                "id=example\nversion=working\n"
            }
        );
        assert_eq!(live.join("update").exists(), committed);
        assert_eq!(live.join("disable").exists(), !committed);
        assert_eq!(live.join("remove").exists(), !committed);
        assert_eq!(
            fs::metadata(live.join("module.prop"))
                .unwrap()
                .permissions()
                .mode()
                & 0o7777,
            0o640
        );
        assert_eq!(fs::read_dir(root.join("transactions")).unwrap().count(), 1);
    }

    fn process_death_publication_matrix(previous: bool) {
        for checkpoint in [
            "publication-journal",
            "publication-swap",
            "publication-metadata",
            "publication-commit",
            "publication-rollback",
            "publication-garbage",
        ] {
            let temp = publication_fixture(previous);
            let root = temp.path();
            let mut child = worker_with(
                "publication",
                root,
                "example",
                &[("MODULE_SAFETY_PAUSE_AT", checkpoint)],
            );
            wait_file(&root.join("paused"));
            assert_eq!(fs::read_to_string(root.join("paused")).unwrap(), checkpoint);
            if checkpoint == "publication-swap" || checkpoint == "publication-metadata" {
                assert!(transaction::is_pending_publication(
                    &root.join("modules_update/example")
                ));
            }
            let journal = PathBuf::from(fs::read_to_string(root.join("journal-path")).unwrap());
            let retained = if checkpoint == "publication-garbage" {
                journal.with_file_name(format!(
                    "gc-{}",
                    journal.file_name().unwrap().to_str().unwrap()
                ))
            } else {
                journal.clone()
            };
            assert!(retained.join("publication").exists());
            if previous && !matches!(checkpoint, "publication-journal" | "publication-rollback") {
                assert_eq!(
                    fs::read_to_string(retained.join("modules/example/version")).unwrap(),
                    "previous-pending"
                );
            }
            child.kill().unwrap();
            assert_eq!(child.wait().unwrap().signal(), Some(libc::SIGKILL));
            let store = transaction::Store::open(
                &root.join("transactions"),
                &root.join("modules_update"),
                &root.join("modules"),
            )
            .unwrap();
            let committed = matches!(checkpoint, "publication-commit" | "publication-garbage");
            assert_publication_recovered(root, previous, committed);
            drop(store);
            // Repeat recovery proves a second boot cannot exchange a backup back in.
            let _again = transaction::Store::open(
                &root.join("transactions"),
                &root.join("modules_update"),
                &root.join("modules"),
            )
            .unwrap();
            assert_publication_recovered(root, previous, committed);
        }
    }

    #[test]
    fn process_death_publication_without_previous_pending() {
        process_death_publication_matrix(false);
    }

    #[test]
    fn process_death_publication_with_previous_pending() {
        process_death_publication_matrix(true);
    }

    #[test]
    fn failed_rollback_and_install_drop_preserve_recovery_material() {
        for previous in [false, true] {
            let temp = publication_fixture(previous);
            let root = temp.path();
            let mut child = worker_with(
                "publication-drop-failure",
                root,
                "example",
                &[("MODULE_SAFETY_FAIL_ROLLBACK", "1")],
            );
            wait_file(&root.join("paused"));
            let journal = PathBuf::from(fs::read_to_string(root.join("journal-path")).unwrap());
            assert!(journal.join("publication").is_file());
            assert!(journal.join("metadata-ready").is_file());
            assert!(transaction::is_pending_publication(
                &root.join("modules_update/example")
            ));
            if previous {
                assert_eq!(
                    fs::read_to_string(journal.join("modules/example/version")).unwrap(),
                    "previous-pending"
                );
            }
            child.kill().unwrap();
            assert_eq!(child.wait().unwrap().signal(), Some(libc::SIGKILL));
            let _recovered = transaction::Store::open(
                &root.join("transactions"),
                &root.join("modules_update"),
                &root.join("modules"),
            )
            .unwrap();
            assert_publication_recovered(root, previous, false);
        }
    }

    #[test]
    fn ambiguous_publication_preserves_all_payloads_and_legacy_backups() {
        for previous in [false, true] {
            let temp = publication_fixture(previous);
            let root = temp.path();
            let mut child = worker_with(
                "publication",
                root,
                "example",
                &[("MODULE_SAFETY_PAUSE_AT", "publication-swap")],
            );
            wait_file(&root.join("paused"));
            child.kill().unwrap();
            child.wait().unwrap();
            let journal = PathBuf::from(fs::read_to_string(root.join("journal-path")).unwrap());
            let update = root.join("modules_update/example");
            fs::rename(&update, root.join("candidate-retained")).unwrap();
            fs::create_dir(&update).unwrap();
            fs::write(update.join("version"), "unrelated").unwrap();
            assert!(
                transaction::Store::open(
                    &root.join("transactions"),
                    &root.join("modules_update"),
                    &root.join("modules")
                )
                .is_err()
            );
            assert!(journal.join("publication").exists());
            assert_eq!(
                fs::read_to_string(root.join("candidate-retained/version")).unwrap(),
                "candidate"
            );
            assert_eq!(
                fs::read_to_string(update.join("version")).unwrap(),
                "unrelated"
            );
            assert_eq!(
                fs::read_to_string(root.join("modules/example/version")).unwrap(),
                "working"
            );
            if previous {
                assert_eq!(
                    fs::read_to_string(journal.join("modules/example/version")).unwrap(),
                    "previous-pending"
                );
            }
        }
        let temp = fixture();
        let (transactions, updates, live) = roots(temp.path());
        let legacy = transactions.join("install-legacy/modules/example");
        fs::create_dir_all(&legacy).unwrap();
        fs::write(legacy.join("version"), "possibly exchanged backup").unwrap();
        assert!(transaction::Store::open(&transactions, &updates, &live).is_err());
        assert_eq!(
            fs::read_to_string(legacy.join("version")).unwrap(),
            "possibly exchanged backup"
        );
    }

    #[test]
    fn missing_or_corrupt_publication_records_preserve_candidates_and_backups() {
        for previous in [false, true] {
            for corruption in [
                "missing-journal",
                "invalid-journal",
                "invalid-commit",
                "dangling-started",
                "orphan-commit",
            ] {
                let temp = publication_fixture(previous);
                let root = temp.path();
                let mut child = worker_with(
                    "publication",
                    root,
                    "example",
                    &[("MODULE_SAFETY_PAUSE_AT", "publication-swap")],
                );
                wait_file(&root.join("paused"));
                child.kill().unwrap();
                child.wait().unwrap();
                let journal = PathBuf::from(fs::read_to_string(root.join("journal-path")).unwrap());
                match corruption {
                    "missing-journal" => fs::remove_file(journal.join("publication")).unwrap(),
                    "invalid-journal" => fs::write(journal.join("publication"), "corrupt").unwrap(),
                    "invalid-commit" => {
                        fs::write(journal.join("committed"), "wrong-transaction").unwrap()
                    }
                    "dangling-started" | "orphan-commit" => {
                        fs::remove_file(journal.join("publication")).unwrap();
                        fs::remove_file(journal.join("publication-started")).unwrap();
                        if corruption == "dangling-started" {
                            std::os::unix::fs::symlink(
                                "missing",
                                journal.join("publication-started"),
                            )
                            .unwrap();
                        } else {
                            fs::write(journal.join("committed"), "orphan-record").unwrap();
                        }
                    }
                    _ => unreachable!(),
                }
                assert!(
                    transaction::Store::open(
                        &root.join("transactions"),
                        &root.join("modules_update"),
                        &root.join("modules")
                    )
                    .is_err()
                );
                assert!(journal.exists());
                assert_eq!(
                    fs::read_to_string(root.join("modules_update/example/version")).unwrap(),
                    "candidate"
                );
                assert!(transaction::is_pending_publication(
                    &root.join("modules_update/example")
                ));
                assert_eq!(
                    fs::read_to_string(root.join("modules/example/version")).unwrap(),
                    "working"
                );
                if previous {
                    assert_eq!(
                        fs::read_to_string(journal.join("modules/example/version")).unwrap(),
                        "previous-pending"
                    );
                }
            }
        }
    }

    #[test]
    fn changed_live_identity_is_not_overwritten_or_deleted_during_recovery() {
        for previous in [false, true] {
            let temp = publication_fixture(previous);
            let root = temp.path();
            let mut child = worker_with(
                "publication",
                root,
                "example",
                &[("MODULE_SAFETY_PAUSE_AT", "publication-metadata")],
            );
            wait_file(&root.join("paused"));
            child.kill().unwrap();
            child.wait().unwrap();
            let journal = PathBuf::from(fs::read_to_string(root.join("journal-path")).unwrap());
            let live = root.join("modules/example");
            fs::rename(&live, root.join("working-retained")).unwrap();
            fs::create_dir(&live).unwrap();
            fs::write(live.join("version"), "unrelated-live").unwrap();
            assert!(
                transaction::Store::open(
                    &root.join("transactions"),
                    &root.join("modules_update"),
                    &root.join("modules")
                )
                .is_err()
            );
            assert_eq!(
                fs::read_to_string(live.join("version")).unwrap(),
                "unrelated-live"
            );
            assert!(!live.join("module.prop").exists());
            assert_eq!(
                fs::read_to_string(root.join("working-retained/version")).unwrap(),
                "working"
            );
            assert_eq!(
                fs::read_to_string(journal.join("metadata/data-module.prop")).unwrap(),
                "id=example\nversion=working\n"
            );
            assert_eq!(
                fs::read_to_string(root.join("modules_update/example/version")).unwrap(),
                "candidate"
            );
            assert!(transaction::is_pending_publication(
                &root.join("modules_update/example")
            ));
            if previous {
                assert_eq!(
                    fs::read_to_string(journal.join("modules/example/version")).unwrap(),
                    "previous-pending"
                );
            }
        }
    }

    #[test]
    fn recovery_distinguishes_pending_update_from_old_exchanged_backup() {
        for committed in [false, true] {
            let temp = fixture();
            let (transactions, updates, modules) = roots(temp.path());
            let journal = transactions.join("promote-fixture");
            fs::create_dir_all(journal.join("payload")).unwrap();
            fs::write(journal.join("module-id"), "example").unwrap();
            fs::write(
                journal.join("payload/version"),
                if committed { "old" } else { "new" },
            )
            .unwrap();
            let live = modules.join("example");
            fs::create_dir(&live).unwrap();
            fs::write(live.join("version"), if committed { "new" } else { "old" }).unwrap();
            if committed {
                fs::write(live.join(".ksu-promotion-token"), "promote-fixture").unwrap();
            } else {
                fs::write(
                    journal.join("payload/.ksu-promotion-token"),
                    "promote-fixture",
                )
                .unwrap();
            }
            let _store = transaction::Store::open(&transactions, &updates, &modules).unwrap();
            assert_eq!(
                fs::read_to_string(live.join("version")).unwrap(),
                if committed { "new" } else { "old" }
            );
            assert_eq!(updates.join("example").exists(), !committed);
            assert!(!journal.exists());
        }
    }
}
