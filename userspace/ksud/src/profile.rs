// Keep the existing owned-String CLI entry points; storage helpers borrow their values.
#![allow(clippy::needless_pass_by_value)]

use crate::utils::ensure_dir_exists;
use crate::{defs, sepolicy};
use anyhow::{Context, Result, ensure};
use rustix::fs::{AtFlags, Mode, OFlags, open, openat, unlinkat};
use std::fs::File;
use std::io::{Read, Write};
use std::os::unix::fs::MetadataExt;
use std::path::Path;

// Same grammar as the Manager template editor: ASCII identifiers separated by dots.
// Validate at the final storage boundary, including reads and deletes, not only in the UI.
fn validate_id(id: &str) -> Result<()> {
    ensure!(
        id.split('.').all(|segment| {
            let mut bytes = segment.bytes();
            bytes.next().is_some_and(|c| c.is_ascii_alphabetic())
                && bytes.all(|c| c.is_ascii_alphanumeric() || c == b'_')
        }),
        "invalid profile ID: expected dot-separated ASCII identifiers"
    );
    Ok(())
}

fn open_directory(base: &Path) -> Result<File> {
    // The parent is root-owned. Pin the storage directory so a renamed directory or
    // a symlink at the final component cannot redirect subsequent operations.
    // Strip a trailing separator: on Linux, `link/` would follow a directory
    // symlink even with O_NOFOLLOW, whereas `link` is rejected.
    let base = base
        .parent()
        .context("profile directory has no parent")?
        .join(base.file_name().context("profile directory has no name")?);
    Ok(File::from(open(
        base,
        OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
        Mode::empty(),
    )?))
}

fn open_profile(directory: &File, id: &str, flags: OFlags) -> Result<File> {
    validate_id(id)?;
    let file = File::from(openat(
        directory,
        id,
        flags | OFlags::NOFOLLOW | OFlags::CLOEXEC | OFlags::NONBLOCK,
        Mode::RUSR | Mode::WUSR,
    )?);
    let metadata = file.metadata()?;
    ensure!(
        metadata.is_file() && metadata.nlink() == 1,
        "profile storage entry is not a private regular file"
    );
    Ok(file)
}

fn write_profile(base: &Path, id: &str, value: &str) -> Result<()> {
    validate_id(id)?; // Reject before even creating the storage directory.
    ensure_dir_exists(base)?;
    let directory = open_directory(base)?;
    // Do not truncate until after checking the opened file, including hard links.
    let mut file = open_profile(&directory, id, OFlags::WRONLY | OFlags::CREATE)?;
    file.set_len(0)?;
    file.write_all(value.as_bytes())?;
    Ok(())
}

fn read_profile(base: &Path, id: &str) -> Result<String> {
    validate_id(id)?;
    let directory = open_directory(base)?;
    let mut file = open_profile(&directory, id, OFlags::RDONLY)?;
    let mut value = String::new();
    file.read_to_string(&mut value)?;
    Ok(value)
}

fn remove_profile(base: &Path, id: &str) -> Result<()> {
    validate_id(id)?;
    let directory = open_directory(base)?;
    // Reject unexpected storage entries consistently with reads/writes. unlinkat
    // never follows the entry even if another writer replaces it after this check.
    let _file = open_profile(&directory, id, OFlags::RDONLY)?;
    unlinkat(&directory, id, AtFlags::empty())?;
    Ok(())
}

pub fn set_sepolicy(pkg: String, policy: String) -> Result<()> {
    write_profile(Path::new(defs::PROFILE_SELINUX_DIR), &pkg, &policy)?;
    // Apply the bytes just saved, without reopening an attacker-replaceable path.
    sepolicy::live_patch(&policy)?;
    Ok(())
}

pub fn get_sepolicy(pkg: String) -> Result<()> {
    let policy = read_profile(Path::new(defs::PROFILE_SELINUX_DIR), &pkg)?;
    println!("{policy}");
    Ok(())
}

// ksud stores template JSON without interpreting its profile/JNI fields.
pub fn set_template(id: String, template: String) -> Result<()> {
    write_profile(Path::new(defs::PROFILE_TEMPLATE_DIR), &id, &template)
}

pub fn get_template(id: String) -> Result<()> {
    let template = read_profile(Path::new(defs::PROFILE_TEMPLATE_DIR), &id)?;
    println!("{template}");
    Ok(())
}

pub fn delete_template(id: String) -> Result<()> {
    remove_profile(Path::new(defs::PROFILE_TEMPLATE_DIR), &id)
}

pub fn list_templates() -> Result<()> {
    let base = Path::new(defs::PROFILE_TEMPLATE_DIR);
    if !base.exists() {
        return Ok(());
    }
    let _directory = open_directory(base)?;
    let templates = std::fs::read_dir(defs::PROFILE_TEMPLATE_DIR);
    let Ok(templates) = templates else {
        return Ok(());
    };
    for template in templates {
        let template = template?;
        if !template.file_type()?.is_file() {
            continue;
        }
        let template = template.file_name();
        if let Some(template) = template.to_str().filter(|id| validate_id(id).is_ok()) {
            println!("{template}");
        }
    }
    Ok(())
}

pub fn apply_sepolies() -> Result<()> {
    let path = Path::new(defs::PROFILE_SELINUX_DIR);
    if !path.exists() {
        log::info!("profile sepolicy dir not exists.");
        return Ok(());
    }

    let sepolicies =
        std::fs::read_dir(path).with_context(|| "profile sepolicy dir open failed.".to_string())?;
    for sepolicy in sepolicies {
        let Ok(sepolicy) = sepolicy else {
            log::info!("profile sepolicy dir read failed.");
            continue;
        };
        let name = sepolicy.file_name();
        let Some(id) = name.to_str() else {
            continue;
        };
        let policy = read_profile(path, id);
        let sepolicy = sepolicy.path();
        if policy
            .and_then(|policy| sepolicy::live_patch(&policy))
            .is_ok()
        {
            log::info!("profile sepolicy applied: {}", sepolicy.display());
        } else {
            log::info!("profile sepolicy apply failed: {}", sepolicy.display());
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::symlink;

    const INVALID_IDS: &[&str] = &[
        "",
        ".",
        "..",
        "../outside",
        "/outside",
        "a/b",
        "a\\b",
        "a..b",
        ".a",
        "a.",
        "0app",
        "a.0app",
        "_app",
        "a-b",
        "a b",
        "a\tb",
        "a\nb",
        "a\rb",
        "a'b",
        "a\"b",
        "a;b",
        "a&b",
        "a|b",
        "$(id)",
        "`id`",
        "a>b",
        "a<b",
        "a*b",
        "a?b",
        "a\0b",
        "a.\u{e9}",
    ];

    #[test]
    fn matches_manager_id_grammar() {
        for id in [
            "a",
            "android",
            "webview_zygote",
            "com.example.App_123",
            "Z9.a_b",
        ] {
            assert!(validate_id(id).is_ok(), "{id:?}");
        }
        for id in INVALID_IDS {
            assert!(validate_id(id).is_err(), "{id:?}");
        }
    }

    #[test]
    fn rejects_invalid_ids_before_creating_or_accessing_storage() {
        let temp = tempfile::tempdir().unwrap();
        let base = temp.path().join("not_created");
        let outside = temp.path().join("outside");
        std::fs::write(&outside, "unchanged").unwrap();
        for id in INVALID_IDS {
            assert!(write_profile(&base, id, "bad").is_err(), "{id:?}");
            assert!(read_profile(&base, id).is_err(), "{id:?}");
            assert!(remove_profile(&base, id).is_err(), "{id:?}");
        }
        assert!(!base.exists());
        assert_eq!(std::fs::read_to_string(outside).unwrap(), "unchanged");
    }

    #[test]
    fn stores_exact_content_and_truncates_previous_value() {
        let temp = tempfile::tempdir().unwrap();
        let base = temp.path().join("templates");
        let value = "'\"\n\t $(not_a_command); & | / \\ \n";
        write_profile(&base, "com.example.App_123", value).unwrap();
        assert_eq!(read_profile(&base, "com.example.App_123").unwrap(), value);
        write_profile(&base, "com.example.App_123", "short").unwrap();
        assert_eq!(read_profile(&base, "com.example.App_123").unwrap(), "short");
        remove_profile(&base, "com.example.App_123").unwrap();
        assert!(!base.join("com.example.App_123").exists());
    }

    #[test]
    fn rejects_symlinks_hardlinks_and_directories_without_modifying_target() {
        let temp = tempfile::tempdir().unwrap();
        let base = temp.path().join("templates");
        std::fs::create_dir(&base).unwrap();
        let outside = temp.path().join("outside");
        std::fs::write(&outside, "unchanged").unwrap();
        symlink(&outside, base.join("symlink")).unwrap();
        std::fs::hard_link(&outside, base.join("hardlink")).unwrap();
        std::fs::create_dir(base.join("directory")).unwrap();
        for id in ["symlink", "hardlink", "directory"] {
            assert!(write_profile(&base, id, "bad").is_err());
            assert!(read_profile(&base, id).is_err());
            assert!(remove_profile(&base, id).is_err());
        }
        let redirect = temp.path().join("redirect");
        symlink(&base, &redirect).unwrap();
        assert!(write_profile(&redirect, "new", "bad").is_err());
        let trailing_separator = format!("{}/", redirect.display());
        assert!(write_profile(Path::new(&trailing_separator), "new", "bad").is_err());
        assert!(!base.join("new").exists());
        assert_eq!(std::fs::read_to_string(outside).unwrap(), "unchanged");
    }
}
