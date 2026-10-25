#![cfg(target_os = "linux")]
#![allow(dead_code)]

#[path = "../../../src/boot_patch.rs"]
mod boot_patch;
#[path = "../../../src/feature.rs"]
mod feature;
#[path = "../../../src/feature_id.rs"]
mod feature_id;
#[path = "../../../src/module/file_lock.rs"]
mod file_lock;
#[path = "../../../src/lkm_image_btf.rs"]
mod lkm_image_btf;
#[path = "../../../src/restart_guard.rs"]
mod restart_guard;
#[path = "../../../src/sepolicy.rs"]
mod sepolicy;
#[path = "../../../src/su_args.rs"]
mod su_args;

mod ksu_uapi {
    #![allow(nonstandard_style)]
    include!(concat!(env!("OUT_DIR"), "/bindings.rs"));
}

// Android boundaries cannot run from the host harness. Tests exercise real
// parsing/persistence code with temporary paths, never these device entrypoints.
mod defs {
    pub const WORKING_DIR: &str = "/unused-host-test-path/";
}
mod assets {
    pub fn get_asset(_: &str) -> anyhow::Result<Box<dyn AsRef<[u8]>>> {
        unreachable!()
    }
}
mod sulog {
    pub fn ensure_sulogd_running() -> anyhow::Result<()> {
        unreachable!()
    }
}
mod utils {
    pub fn ensure_dir_exists(_: impl AsRef<std::path::Path>) -> anyhow::Result<()> {
        unreachable!()
    }
}
mod module {
    pub fn get_managed_features() -> anyhow::Result<std::collections::HashMap<String, Vec<String>>>
    {
        unreachable!()
    }
}
mod ksucalls {
    #[derive(Default)]
    pub struct State {
        pub supported: bool,
        pub forced: bool,
        pub value: u64,
    }
    pub fn get_feature(_: u32) -> anyhow::Result<State> {
        unreachable!()
    }
    pub fn set_feature(_: u32, _: u64) -> anyhow::Result<()> {
        unreachable!()
    }
    pub fn set_sepolicy(_: *const u8, _: u64) -> anyhow::Result<i32> {
        unreachable!()
    }
}
