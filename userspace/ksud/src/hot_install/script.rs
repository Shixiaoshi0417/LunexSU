use anyhow::{Result, bail};
use std::{
    fs::canonicalize,
    path::{Component, Path, PathBuf},
};

/// Resolve the actual file relative to the module root, so an absolute symlink into the
/// staging directory is not followed again after that directory is moved into place.
pub(super) fn check_run_script(staged_dir: &Path, script: &str) -> Result<Option<PathBuf>> {
    let mut components = Path::new(script).components().peekable();
    while components.next_if_eq(&Component::CurDir).is_some() {}
    let relative: PathBuf = components.collect();
    if relative.as_os_str().is_empty()
        || !relative
            .components()
            .all(|c| matches!(c, Component::Normal(_)))
    {
        bail!("invalid MODULE_HOT_RUN_SCRIPT '{script}'");
    }

    let path = staged_dir.join(&relative);
    if path.symlink_metadata().is_err() {
        println!("! MODULE_HOT_RUN_SCRIPT '{script}' not found, skipping it");
        return Ok(None);
    }
    let root = canonicalize(staged_dir)?;
    let resolved = canonicalize(&path)?;
    if !resolved.is_file() || !resolved.starts_with(&root) {
        bail!("MODULE_HOT_RUN_SCRIPT '{script}' is not a file inside the module");
    }
    Ok(Some(resolved.strip_prefix(root)?.to_path_buf()))
}
