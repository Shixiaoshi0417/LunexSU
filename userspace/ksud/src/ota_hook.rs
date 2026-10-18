//! Durable OTA completion hook, with no partition access.
use super::lifecycle::OtaHook;
use crate::file_lock::FileLock;
use std::{
    fs::{self, File, OpenOptions, Permissions},
    io::{self, Read, Write},
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
};

pub struct FileHook<'a> {
    adb: &'a Path,
    bootctl: &'a Path,
    _lock: FileLock,
}

impl<'a> FileHook<'a> {
    pub fn new(adb: &'a Path, bootctl: &'a Path) -> io::Result<Self> {
        Ok(Self {
            adb,
            bootctl,
            _lock: FileLock::exclusive(&adb.join(".post-ota.lock"))?,
        })
    }

    fn directory(&self) -> PathBuf {
        self.adb.join("post-fs-data.d")
    }

    fn path(&self) -> PathBuf {
        self.directory().join("post_ota.sh")
    }

    fn script(&self, target: &str) -> io::Result<String> {
        if !matches!(target, "0" | "1") {
            return Err(io::Error::other("invalid OTA hook target slot"));
        }
        let quote = |path: &Path| -> io::Result<String> {
            let path = path
                .to_str()
                .ok_or_else(|| io::Error::other("OTA hook path is not UTF-8"))?;
            Ok(format!("'{}'", path.replace('\'', "'\\''")))
        };
        let bootctl = quote(self.bootctl)?;
        let hook = quote(&self.path())?;
        // An interruption before activation must not mark the original slot.
        Ok(format!(
            "#!/system/bin/sh\n\
             current=$({bootctl} get-current-slot) || exit $?\n\
             [ \"$current\" = '{target}' ] || exit 0\n\
             {bootctl} mark-boot-successful || exit $?\n\
             rm -f {bootctl} || exit $?\n\
             rm -f {hook}\n"
        ))
    }
}

impl OtaHook for FileHook<'_> {
    fn prepare(&mut self, target: &str) -> io::Result<()> {
        let script = self.script(target)?;
        checkpoint("create-dir")?;
        fs::create_dir_all(self.directory())?;

        // Stage outside the executable script directory: even an interrupted
        // chmod must not leave an extra script that post-fs-data could execute.
        let mut temporary = tempfile::Builder::new()
            .prefix(".post-ota-")
            .tempfile_in(self.adb)?;
        checkpoint("write")?;
        temporary.write_all(script.as_bytes())?;
        checkpoint("chmod")?;
        temporary
            .as_file()
            .set_permissions(Permissions::from_mode(0o755))?;
        checkpoint("file-sync")?;
        temporary.as_file().sync_all()?;
        checkpoint("publish")?;
        match temporary.persist_noclobber(self.path()) {
            Ok(_) => {}
            Err(error) if error.error.kind() == io::ErrorKind::AlreadyExists => {
                // A previous attempt may have stopped after publication. Reuse
                // only the identical hook; never replace another pending OTA.
                let mut existing = OpenOptions::new()
                    .read(true)
                    .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
                    .open(self.path())?;
                let metadata = existing.metadata()?;
                if !metadata.is_file()
                    || metadata.len() != script.len() as u64
                    || metadata.permissions().mode() & 0o7777 != 0o755
                {
                    return Err(io::Error::other("conflicting pending OTA hook"));
                }
                let mut content = String::new();
                existing.read_to_string(&mut content)?;
                if content != script {
                    return Err(io::Error::other("conflicting pending OTA hook"));
                }
                existing.sync_all()?;
            }
            Err(error) => return Err(error.error),
        }
        checkpoint("published")?;
        File::open(self.directory())?.sync_all()?;
        checkpoint("directory-synced")?;
        // This also persists a newly created post-fs-data.d directory.
        File::open(self.adb)?.sync_all()?;
        checkpoint("parent-synced")?;
        // The completion script must still have a durable bootctl after reboot.
        File::open(self.bootctl)?.sync_all()?;
        checkpoint("bootctl-synced")?;
        let parent = self
            .bootctl
            .parent()
            .ok_or_else(|| io::Error::other("bootctl has no parent"))?;
        if !parent.starts_with(self.adb) {
            return Err(io::Error::other("bootctl is outside the ADB directory"));
        }
        for directory in parent
            .ancestors()
            .take_while(|path| path.starts_with(self.adb))
        {
            File::open(directory)?.sync_all()?;
        }
        checkpoint("ready")
    }

    fn remove(&mut self) -> io::Result<()> {
        checkpoint("remove")?;
        fs::remove_file(self.path())?;
        checkpoint("cleanup-sync")?;
        File::open(self.directory())?.sync_all()
    }
}

#[cfg(not(test))]
// Keep the same fallible checkpoint signature used by the fault-injection build.
#[allow(clippy::unnecessary_wraps)]
const fn checkpoint(_: &str) -> io::Result<()> {
    Ok(())
}

#[cfg(test)]
fn checkpoint(name: &str) -> io::Result<()> {
    tests::checkpoint(name)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{cell::RefCell, process::Command};

    const STEPS: &[&str] = &[
        "create-dir",
        "write",
        "chmod",
        "file-sync",
        "publish",
        "published",
        "directory-synced",
        "parent-synced",
        "bootctl-synced",
        "ready",
    ];
    thread_local! {
        static FAIL: RefCell<Option<&'static str>> = const { RefCell::new(None) };
    }

    pub(super) fn checkpoint(name: &str) -> io::Result<()> {
        if std::env::var("LUNEXSU_OTA_STOP_AT").ok().as_deref() == Some(name) {
            std::process::exit(78); // No Drop/unwind: model an abrupt process exit.
        }
        FAIL.with(|fail| {
            if *fail.borrow() == Some(name) {
                return Err(io::Error::other(format!("injected {name} failure")));
            }
            Ok(())
        })
    }

    fn fixture() -> tempfile::TempDir {
        let fixture = tempfile::Builder::new()
            .prefix("ota fixture's ")
            .tempdir()
            .unwrap();
        fs::create_dir(fixture.path().join("bin")).unwrap();
        fs::write(
            fixture.path().join("bin/bootctl"),
            b"fake bootctl, never executed",
        )
        .unwrap();
        fixture
    }

    #[test]
    fn every_preparation_failure_prevents_activation_and_is_retryable() {
        for step in STEPS {
            let fixture = fixture();
            let bootctl = fixture.path().join("bin/bootctl");
            let mut hook = FileHook::new(fixture.path(), &bootctl).unwrap();
            let mut controller = RecordingBootctl(fixture.path().to_path_buf());
            let plan = super::super::lifecycle::prepare_ota(&mut controller, "_a").unwrap();
            FAIL.with(|fail| *fail.borrow_mut() = Some(step));
            assert!(
                super::super::lifecycle::activate_ota(&mut controller, &mut hook, &plan).is_err(),
                "{step}"
            );
            assert!(!fixture.path().join("activated").exists());
            FAIL.with(|fail| *fail.borrow_mut() = None);
            super::super::lifecycle::activate_ota(&mut controller, &mut hook, &plan).unwrap();
            assert_eq!(
                fs::read_to_string(hook.path()).unwrap(),
                hook.script("1").unwrap()
            );
            assert_eq!(
                fs::metadata(hook.path()).unwrap().permissions().mode() & 0o7777,
                0o755
            );
            assert_eq!(fs::read_dir(hook.directory()).unwrap().count(), 1);
        }
    }

    #[test]
    fn conflicting_or_symlink_hooks_are_not_overwritten() {
        for symlink in [false, true] {
            let fixture = fixture();
            let bootctl = fixture.path().join("bin/bootctl");
            let mut hook = FileHook::new(fixture.path(), &bootctl).unwrap();
            fs::create_dir(hook.directory()).unwrap();
            if symlink {
                std::os::unix::fs::symlink(&bootctl, hook.path()).unwrap();
            } else {
                fs::write(hook.path(), "existing pending OTA").unwrap();
            }
            assert!(hook.prepare("1").is_err());
            assert_eq!(fs::read(&bootctl).unwrap(), b"fake bootctl, never executed");
            if symlink {
                assert!(fs::symlink_metadata(hook.path()).unwrap().is_symlink());
            } else {
                assert_eq!(
                    fs::read_to_string(hook.path()).unwrap(),
                    "existing pending OTA"
                );
            }
        }
    }

    #[test]
    fn failed_cleanup_is_reported_and_retryable() {
        for step in ["remove", "cleanup-sync"] {
            let fixture = fixture();
            let bootctl = fixture.path().join("bin/bootctl");
            let mut hook = FileHook::new(fixture.path(), &bootctl).unwrap();
            hook.prepare("1").unwrap();
            FAIL.with(|fail| *fail.borrow_mut() = Some(step));
            assert!(hook.remove().is_err());
            FAIL.with(|fail| *fail.borrow_mut() = None);
            hook.prepare("1").unwrap();
            hook.remove().unwrap();
            assert!(!hook.path().exists());
        }
    }

    #[test]
    fn script_checks_target_and_retains_hook_and_bootctl_on_mark_failure() {
        let fixture = fixture();
        let bootctl = fixture.path().join("bin/bootctl");
        fs::write(&bootctl, "#!/bin/sh\ncase \"$1\" in\nget-current-slot) echo \"$CURRENT\";;\nmark-boot-successful) exit \"$MARK_STATUS\";;\n*) exit 99;;\nesac\n").unwrap();
        fs::set_permissions(&bootctl, Permissions::from_mode(0o755)).unwrap();
        let mut hook = FileHook::new(fixture.path(), &bootctl).unwrap();
        hook.prepare("1").unwrap();
        for (current, mark_status, success) in [("0", "0", true), ("1", "23", false)] {
            let status = Command::new("sh")
                .arg(hook.path())
                .env("CURRENT", current)
                .env("MARK_STATUS", mark_status)
                .status()
                .unwrap();
            assert_eq!(status.success(), success);
            assert!(bootctl.exists());
            assert!(hook.path().exists());
        }
        assert!(
            Command::new("sh")
                .arg(hook.path())
                .env("CURRENT", "1")
                .env("MARK_STATUS", "0")
                .status()
                .unwrap()
                .success()
        );
        assert!(!hook.path().exists());
        assert!(!bootctl.exists());
    }

    struct RecordingBootctl(PathBuf);

    impl super::super::lifecycle::Bootctl for RecordingBootctl {
        fn run(&mut self, args: &[&str]) -> io::Result<super::super::lifecycle::BootctlOutput> {
            let stdout = match args {
                ["hal-info"] => Vec::new(),
                ["get-current-slot"] => b"0\n".to_vec(),
                ["set-active-boot-slot", "1"] => {
                    fs::write(self.0.join("activated"), "1")?;
                    checkpoint("activated")?;
                    Vec::new()
                }
                _ => panic!("unexpected fake bootctl arguments: {args:?}"),
            };
            Ok(super::super::lifecycle::BootctlOutput {
                success: true,
                stdout,
            })
        }
    }

    #[test]
    #[ignore = "worker explicitly launched by the process interruption test"]
    fn crash_worker() {
        let root = PathBuf::from(std::env::var_os("LUNEXSU_OTA_FIXTURE").unwrap());
        let bootctl_path = root.join("bin/bootctl");
        let mut hook = FileHook::new(&root, &bootctl_path).unwrap();
        let mut bootctl = RecordingBootctl(root.clone());
        let plan = super::super::lifecycle::prepare_ota(&mut bootctl, "_a").unwrap();
        super::super::lifecycle::activate_ota(&mut bootctl, &mut hook, &plan).unwrap();
    }

    #[test]
    fn interruption_never_selects_target_without_a_complete_hook() {
        for step in STEPS.iter().copied().chain(["activated"]) {
            let fixture = fixture();
            let status = Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "ota_hook::tests::crash_worker",
                    "--ignored",
                    "--nocapture",
                ])
                .env("LUNEXSU_OTA_FIXTURE", fixture.path())
                .env("LUNEXSU_OTA_STOP_AT", step)
                .status()
                .unwrap();
            assert_eq!(status.code(), Some(78), "{step}");
            assert_eq!(
                fixture.path().join("activated").exists(),
                step == "activated"
            );
            let bootctl_path = fixture.path().join("bin/bootctl");
            let mut hook = FileHook::new(fixture.path(), &bootctl_path).unwrap();
            if fixture.path().join("activated").exists() {
                assert_eq!(
                    fs::read_to_string(hook.path()).unwrap(),
                    hook.script("1").unwrap()
                );
                assert_eq!(
                    fs::metadata(hook.path()).unwrap().permissions().mode() & 0o7777,
                    0o755
                );
            }
            hook.prepare("1").unwrap(); // Retry reuses complete published hooks.
        }
    }
}
