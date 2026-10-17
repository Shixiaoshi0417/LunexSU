//! Host-only tests of production profile storage. Never links the daemon or kernel calls.
#![allow(dead_code)]

mod defs {
    // Public entry points are only called with invalid IDs, rejected before touching these paths.
    pub const PROFILE_SELINUX_DIR: &str = "/F01_TEST_MUST_NOT_ACCESS/selinux/";
    pub const PROFILE_TEMPLATE_DIR: &str = "/F01_TEST_MUST_NOT_ACCESS/templates/";
}

mod utils {
    pub fn ensure_dir_exists(path: impl AsRef<std::path::Path>) -> anyhow::Result<()> {
        std::fs::create_dir_all(path)?;
        Ok(())
    }
}

mod sepolicy {
    pub fn live_patch(_policy: &str) -> anyhow::Result<()> {
        panic!("Host regression must not apply a policy");
    }
}

#[path = "../../../../userspace/ksud/src/profile.rs"]
mod profile;
