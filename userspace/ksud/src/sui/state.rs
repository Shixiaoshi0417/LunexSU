//! Desired state is independent of module flags and never starts a server on its own.
use anyhow::{Result, ensure};
use serde::{Deserialize, Serialize};

#[derive(Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct State {
    pub schema_version: u32,
    pub enabled: bool,
    pub requested_boot: Option<String>,
}

impl Default for State {
    fn default() -> Self {
        Self {
            schema_version: 1,
            enabled: false,
            requested_boot: None,
        }
    }
}

pub fn valid_boot_id(value: &str) -> bool {
    value.len() == 36
        && value.bytes().enumerate().all(|(i, byte)| {
            if [8, 13, 18, 23].contains(&i) {
                byte == b'-'
            } else {
                byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase()
            }
        })
}

impl State {
    pub fn parse(bytes: &[u8]) -> Result<Self> {
        ensure!(bytes.len() <= 4096, "Oversized Sui state");
        let state: Self = serde_json::from_slice(bytes)?;
        ensure!(state.schema_version == 1, "Unknown Sui state schema");
        ensure!(
            !state.enabled || state.requested_boot.is_some(),
            "Missing Sui activation boot"
        );
        ensure!(
            state.requested_boot.as_deref().is_none_or(valid_boot_id),
            "Invalid Sui boot ID"
        );
        Ok(state)
    }

    pub fn request(&self, enabled: bool, boot: &str) -> Result<Self> {
        ensure!(valid_boot_id(boot), "Invalid current boot ID");
        // Repeating enable must not stop a healthy service until another reboot.
        let requested_boot = if enabled && self.enabled {
            self.requested_boot.clone()
        } else if enabled {
            Some(boot.to_owned())
        } else {
            None
        };
        Ok(Self {
            schema_version: 1,
            enabled,
            requested_boot,
        })
    }

    pub fn after_reboot(&self, boot: &str) -> bool {
        valid_boot_id(boot)
            && self.enabled
            && self
                .requested_boot
                .as_deref()
                .is_some_and(|old| old != boot)
    }
}

// Do not load the vendor's adbd transitions when enabling the root/shell broker.
// Keep this domain aligned with kernel/selinux/selinux.h, checked by the host suite.
pub const POLICY: &[u8] = b"# Managed Sui root/shell broker; no ADB Root permissions.\nallow ksu ksu process setcurrent\nallow ksu shell process dyntransition\n";
