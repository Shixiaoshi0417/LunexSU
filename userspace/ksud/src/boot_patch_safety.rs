use android_bootimg::cpio::{Cpio, CpioEntry};
use anyhow::{Context, Result, ensure};

const ADB_BACKUP: &str = ".ksu_adb_backup";
const ADB_FILES: [&str; 2] = ["force_debuggable", "adb_debug.prop"];

#[cfg(any(target_os = "android", test))]
pub const fn is_gki_version(version: (i32, i32, i32)) -> bool {
    version.0 > 5 || (version.0 == 5 && version.1 >= 10)
}

/// Do not guess which debug files in an older patched image belonged to stock.
pub fn ensure_tracked_adb(cpio: &Cpio) -> Result<()> {
    ensure!(
        cpio.exists(ADB_BACKUP) || !ADB_FILES.iter().any(|name| cpio.exists(name)),
        "Untracked adb debug files in patched image; restore a stock boot backup before repatching/restoring"
    );
    Ok(())
}

pub fn restore_adb(cpio: &mut Cpio) -> Result<()> {
    let Some(entry) = cpio.entry_by_name(ADB_BACKUP) else {
        return Ok(());
    };
    let original = Cpio::load_from_data(entry.data().unwrap_or_default())
        .context("read original adb debug files")?;
    // Merge the original archive so modes, ownership and file
    // types are preserved, including an originally empty force_debuggable.
    for name in ADB_FILES {
        cpio.rm(name, false);
    }
    let mut data = Vec::new();
    cpio.rm(ADB_BACKUP, false);
    cpio.dump(&mut data)?;
    original.dump(&mut data)?;
    *cpio = Cpio::load_from_data(&data)?;
    Ok(())
}

pub fn apply_adb(cpio: &mut Cpio, prop: Option<Vec<u8>>) -> Result<()> {
    if !cpio.exists(ADB_BACKUP) {
        // Serialize only the original entries, preserving their full metadata.
        let mut data = Vec::new();
        cpio.dump(&mut data)?;
        let mut original = Cpio::load_from_data(&data)?;
        let names: Vec<_> = original.entries().keys().cloned().collect();
        for name in names {
            if !ADB_FILES.contains(&name.as_str()) {
                original.rm(&name, false);
            }
        }
        data.clear();
        original.dump(&mut data)?;
        cpio.add(ADB_BACKUP, CpioEntry::regular(0o600, Box::new(data)))?;
    }
    let Some(prop) = prop else {
        // Keep provenance while LunexSU remains installed, including when the
        // original stock image already had debug files and no override was set.
        let backup = cpio
            .entry_by_name(ADB_BACKUP)
            .and_then(CpioEntry::data)
            .context("missing original adb debug archive")?
            .to_vec();
        restore_adb(cpio)?;
        cpio.add(ADB_BACKUP, CpioEntry::regular(0o600, Box::new(backup)))?;
        return Ok(());
    };
    cpio.add(
        "force_debuggable",
        CpioEntry::regular(0o644, Box::new(Vec::<u8>::new())),
    )?;
    cpio.add("adb_debug.prop", CpioEntry::regular(0o644, Box::new(prop)))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dump(cpio: &Cpio) -> Vec<u8> {
        let mut data = Vec::new();
        cpio.dump(&mut data).unwrap();
        data
    }

    #[test]
    fn gki_uses_major_and_minor_not_patchlevel() {
        for version in [(6, 1, 0), (6, 6, 5), (5, 10, 0), (5, 15, 200)] {
            assert!(is_gki_version(version));
        }
        for version in [(4, 19, 200), (5, 4, 200), (5, 9, 100)] {
            assert!(!is_gki_version(version));
        }
    }

    #[test]
    fn repatch_then_restore_removes_injected_debug_files() {
        let mut cpio = Cpio::new();
        let stock = dump(&cpio);
        apply_adb(&mut cpio, Some(b"ro.adb.secure=0\n".to_vec())).unwrap();
        apply_adb(&mut cpio, Some(b"ro.debuggable=1\n".to_vec())).unwrap();
        ensure_tracked_adb(&cpio).unwrap();
        restore_adb(&mut cpio).unwrap();
        assert_eq!(dump(&cpio), stock);
    }

    #[test]
    fn stock_debug_files_and_metadata_survive_patch_and_restore() {
        let mut cpio = Cpio::new();
        cpio.add(
            "force_debuggable",
            CpioEntry::regular(0o400, Box::new(vec![] as Vec<u8>)),
        )
        .unwrap();
        cpio.add(
            "adb_debug.prop",
            CpioEntry::regular(0o640, Box::new(b"ro.adb.secure=1\n".to_vec())),
        )
        .unwrap();
        let stock = dump(&cpio);
        apply_adb(&mut cpio, None).unwrap();
        ensure_tracked_adb(&cpio).unwrap();
        apply_adb(&mut cpio, Some(b"ro.adb.secure=0\n".to_vec())).unwrap();
        apply_adb(&mut cpio, None).unwrap();
        ensure_tracked_adb(&cpio).unwrap();
        restore_adb(&mut cpio).unwrap();
        assert_eq!(dump(&cpio), stock);
    }

    #[test]
    fn legacy_untracked_debug_files_require_stock_backup() {
        let mut cpio = Cpio::new();
        cpio.add(
            "adb_debug.prop",
            CpioEntry::regular(0o644, Box::new(b"ro.adb.secure=0\n".to_vec())),
        )
        .unwrap();
        assert!(ensure_tracked_adb(&cpio).is_err());
    }
}
