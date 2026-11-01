use crate::provider::{self, State};
use std::{
    ffi::CString,
    fs,
    os::unix::fs::symlink,
    path::{Path, PathBuf},
};
use tempfile::{TempDir, tempdir};

struct Tree {
    temp: TempDir,
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
        Self { temp, live, staged }
    }

    fn module(&self, root: &Path, id: &str) -> PathBuf {
        let path = root.join(id);
        fs::create_dir_all(&path).unwrap();
        fs::write(path.join("module.prop"), format!("id={id}\n")).unwrap();
        path
    }

    fn inspect(&self) -> provider::Assessment {
        provider::inspect(&self.live, &self.staged).unwrap()
    }
}

fn fifo(path: &Path) {
    let path = CString::new(path.as_os_str().as_encoded_bytes()).unwrap();
    assert_eq!(unsafe { libc::mkfifo(path.as_ptr(), 0o600) }, 0);
}

#[test]
fn missing_roots_and_empty_trees_are_missing_and_not_created() {
    let tree = Tree::new();
    assert_eq!(tree.inspect().state, State::Missing);
    let missing = tree.temp.path().join("absent");
    let result = provider::inspect(&missing, &missing).unwrap();
    assert_eq!(result.state, State::Missing);
    assert!(result.providers.is_empty());
    assert!(!missing.exists());
    assert!(result.require_ready().is_err());
}

#[test]
fn either_external_provider_is_ready_without_any_ownership_marker() {
    for id in ["rezygisk", "zygisksu"] {
        let tree = Tree::new();
        let module = tree.module(&tree.live, id);
        let before = fs::read_dir(&module).unwrap().count();
        let result = tree.inspect();
        assert_eq!(result.state, State::Ready);
        assert_eq!(result.providers, [id]);
        result.require_ready().unwrap();
        assert!(!module.join(".lunexsu_builtin_zygisk").exists());
        assert!(!module.join("disable").exists());
        assert_eq!(fs::read_dir(&module).unwrap().count(), before);
    }
}

#[test]
fn builtin_and_external_use_identical_prerequisites() {
    let tree = Tree::new();
    let module = tree.module(&tree.live, "rezygisk");
    fs::write(module.join(".lunexsu_builtin_zygisk"), "").unwrap();
    assert_eq!(tree.inspect().state, State::Ready);
    fs::remove_file(module.join(".lunexsu_builtin_zygisk")).unwrap();
    assert_eq!(tree.inspect().state, State::Ready);
}

#[test]
fn ordinary_plugins_names_and_zygisk_libraries_are_not_providers() {
    let tree = Tree::new();
    for id in [
        "lsposed",
        "sui",
        "zygisknextsu",
        "some_plugin",
        "zygisknext",
    ] {
        let path = tree.module(&tree.live, id);
        fs::create_dir(path.join("zygisk")).unwrap();
        fs::write(path.join("module.prop"), "name=ReZygisk\n").unwrap();
    }
    assert_eq!(tree.inspect().state, State::Missing);
    assert!(tree.inspect().providers.is_empty());
}

#[test]
fn disabled_provider_does_not_satisfy_enable() {
    let tree = Tree::new();
    let module = tree.module(&tree.live, "zygisksu");
    fs::write(module.join("disable"), "retain").unwrap();
    assert_eq!(tree.inspect().state, State::Missing);
    assert_eq!(
        fs::read_to_string(module.join("disable")).unwrap(),
        "retain"
    );
}

#[test]
fn removed_or_updating_live_provider_requires_reboot() {
    for flag in ["remove", "update"] {
        let tree = Tree::new();
        let module = tree.module(&tree.live, "rezygisk");
        fs::write(module.join(flag), "").unwrap();
        let result = tree.inspect();
        assert_eq!(result.state, State::PendingReboot);
        assert!(result.require_ready().is_err());
    }
}

#[test]
fn stage_only_install_never_counts_as_current_boot_ready() {
    let tree = Tree::new();
    tree.module(&tree.staged, "rezygisk");
    assert_eq!(tree.inspect().state, State::PendingReboot);
    assert!(!tree.live.join("rezygisk").exists());
}

#[test]
fn every_staged_copy_even_disabled_requires_reboot() {
    for flag in [None, Some("disable"), Some("remove")] {
        let tree = Tree::new();
        tree.module(&tree.live, "rezygisk");
        let staged = tree.module(&tree.staged, "rezygisk");
        if let Some(flag) = flag {
            fs::write(staged.join(flag), "").unwrap();
        }
        assert_eq!(tree.inspect().state, State::PendingReboot);
    }
}

#[test]
fn two_enabled_or_staged_provider_ids_are_conflicting() {
    for stage_second in [false, true] {
        let tree = Tree::new();
        tree.module(&tree.live, "rezygisk");
        tree.module(
            if stage_second {
                &tree.staged
            } else {
                &tree.live
            },
            "zygisksu",
        );
        let result = tree.inspect();
        assert_eq!(result.state, State::Conflict);
        assert_eq!(result.providers, ["rezygisk", "zygisksu"]);
        assert!(result.require_ready().is_err());
    }
}

#[test]
fn disabled_other_provider_is_inventory_not_a_conflict() {
    let tree = Tree::new();
    tree.module(&tree.live, "rezygisk");
    let other = tree.module(&tree.live, "zygisksu");
    fs::write(other.join("disable"), "").unwrap();
    let result = tree.inspect();
    assert_eq!(result.state, State::Ready);
    assert_eq!(result.providers, ["rezygisk", "zygisksu"]);
    assert!(other.join("disable").exists());
}

#[test]
fn every_control_entry_type_is_a_marker_not_a_file_to_follow_or_open() {
    for flag in ["disable", "remove", "update"] {
        for kind in ["dangling", "fifo", "directory"] {
            let tree = Tree::new();
            let module = tree.module(&tree.live, "rezygisk");
            let path = module.join(flag);
            match kind {
                "dangling" => symlink(tree.temp.path().join("absent"), &path).unwrap(),
                "fifo" => fifo(&path),
                _ => fs::create_dir(&path).unwrap(),
            }
            let result = tree.inspect();
            assert_eq!(
                result.state,
                if flag == "disable" {
                    State::Missing
                } else {
                    State::PendingReboot
                }
            );
            assert!(result.require_ready().is_err());
            assert!(fs::symlink_metadata(path).is_ok());
        }
    }
}

#[test]
fn unsafe_metadata_cannot_prove_a_provider_and_is_not_read() {
    for kind in ["missing", "symlink", "fifo", "directory"] {
        let tree = Tree::new();
        let module = tree.module(&tree.live, "rezygisk");
        let prop = module.join("module.prop");
        fs::remove_file(&prop).unwrap();
        match kind {
            "symlink" => symlink(tree.temp.path().join("absent"), &prop).unwrap(),
            "fifo" => fifo(&prop),
            "directory" => fs::create_dir(&prop).unwrap(),
            _ => {}
        }
        assert!(provider::inspect(&tree.live, &tree.staged).is_err());
    }
}

#[test]
fn module_and_root_symlinks_are_rejected_without_outside_writes() {
    let tree = Tree::new();
    let outside = tree.module(tree.temp.path(), "outside");
    symlink(&outside, tree.live.join("rezygisk")).unwrap();
    assert!(provider::inspect(&tree.live, &tree.staged).is_err());
    assert_eq!(fs::read_dir(&outside).unwrap().count(), 1);
    fs::remove_file(tree.live.join("rezygisk")).unwrap();
    fs::remove_dir(&tree.staged).unwrap();
    symlink(&outside, &tree.staged).unwrap();
    assert!(provider::inspect(&tree.live, &tree.staged).is_err());
    assert_eq!(fs::read_dir(&outside).unwrap().count(), 1);
}

#[test]
fn non_directory_or_dangling_provider_paths_are_unavailable_not_ready() {
    for kind in ["file", "fifo", "dangling"] {
        let tree = Tree::new();
        let path = tree.live.join("rezygisk");
        match kind {
            "file" => fs::write(&path, "not a directory").unwrap(),
            "fifo" => fifo(&path),
            _ => symlink(tree.temp.path().join("absent"), &path).unwrap(),
        }
        assert!(provider::inspect(&tree.live, &tree.staged).is_err());
    }
}

#[test]
fn a_fresh_check_rejects_provider_loss_after_an_earlier_ready_result() {
    let tree = Tree::new();
    let module = tree.module(&tree.live, "zygisksu");
    tree.inspect().require_ready().unwrap();
    fs::write(module.join("disable"), "").unwrap();
    assert!(tree.inspect().require_ready().is_err());
    fs::remove_file(module.join("disable")).unwrap();
    tree.inspect().require_ready().unwrap();
}

#[test]
fn json_assessment_has_bounded_known_ids_and_stable_states() {
    assert_eq!(
        serde_json::to_value(provider::Assessment::unavailable()).unwrap(),
        serde_json::json!({"state": "unavailable", "providers": []})
    );
    let tree = Tree::new();
    tree.module(&tree.live, "zygisksu");
    assert_eq!(
        serde_json::to_value(tree.inspect()).unwrap(),
        serde_json::json!({"state": "ready", "providers": ["zygisksu"]})
    );
}
