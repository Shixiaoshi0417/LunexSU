#![cfg(unix)]

#[path = "../src/hot_install/script.rs"]
mod script;

use script::check_run_script;
use std::{fs, os::unix::fs::symlink, path::Path, process::Command};

#[test]
fn absolute_symlink_target_survives_promotion() {
    let temp = tempfile::tempdir().unwrap();
    let staged = temp.path().join("modules_update/example");
    fs::create_dir_all(staged.join("scripts")).unwrap();
    fs::write(staged.join("scripts/run.sh"), "printf hot-install-ok").unwrap();
    symlink(staged.join("scripts/run.sh"), staged.join("action.sh")).unwrap();
    let script = check_run_script(&staged, "action.sh").unwrap().unwrap();
    assert_eq!(script, Path::new("scripts/run.sh"));
    let live = temp.path().join("example");
    fs::rename(&staged, &live).unwrap();
    assert!(fs::read(live.join("action.sh")).is_err());
    let output = Command::new("sh").arg(live.join(script)).output().unwrap();
    assert!(output.status.success());
    assert_eq!(output.stdout, b"hot-install-ok");
}

#[test]
fn relative_symlink_and_plain_file_resolve_to_same_target() {
    let temp = tempfile::tempdir().unwrap();
    fs::write(temp.path().join("run.sh"), "exit 0").unwrap();
    symlink("run.sh", temp.path().join("action.sh")).unwrap();
    for name in ["action.sh", "./run.sh"] {
        assert_eq!(
            check_run_script(temp.path(), name).unwrap().unwrap(),
            Path::new("run.sh")
        );
    }
}

#[test]
fn symlinks_outside_module_and_directories_are_rejected() {
    let temp = tempfile::tempdir().unwrap();
    let staged = temp.path().join("example");
    fs::create_dir(&staged).unwrap();
    fs::write(temp.path().join("outside.sh"), "exit 0").unwrap();
    symlink("../outside.sh", staged.join("action.sh")).unwrap();
    assert!(check_run_script(&staged, "action.sh").is_err());
    fs::create_dir(staged.join("directory")).unwrap();
    assert!(check_run_script(&staged, "directory").is_err());
}

#[test]
fn missing_script_is_skipped_but_invalid_paths_are_rejected() {
    let temp = tempfile::tempdir().unwrap();
    assert_eq!(check_run_script(temp.path(), "missing.sh").unwrap(), None);
    for name in ["", ".", "../outside.sh", "/absolute.sh", "scripts/../run.sh"] {
        assert!(check_run_script(temp.path(), name).is_err(), "{name}");
    }
}
