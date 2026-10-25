#[cfg(test)]
#[path = "../../../src/sui/payload.rs"]
mod payload;

#[cfg(test)]
#[path = "../../../src/sui/identity.rs"]
mod identity;

#[cfg(test)]
mod identity_tests;

#[cfg(all(test, unix))]
#[path = "../../../src/sui/process_helper.rs"]
mod process_helper;

#[cfg(all(test, unix))]
#[path = "../../../src/sui/supervision.rs"]
mod supervision;

#[cfg(all(test, unix))]
#[path = "../../../src/module/file_lock.rs"]
mod file_lock;
#[cfg(all(test, unix))]
#[path = "../../../src/hot_install/script.rs"]
mod hot_install;
#[cfg(all(test, unix))]
#[path = "../../../src/module/transaction.rs"]
pub mod transaction;
#[cfg(all(test, unix))]
mod module {
    pub(crate) use crate::transaction;
}
#[cfg(all(test, unix))]
#[path = "../../../src/sui/deployment.rs"]
mod deployment;

#[cfg(all(test, unix))]
#[path = "../../../src/sui/provider.rs"]
mod provider;
#[cfg(all(test, unix))]
mod provider_tests;

#[cfg(test)]
mod tests {
    use super::payload;
    use serde_json::{Value, json};
    use std::{cell::RefCell, collections::BTreeMap};

    fn fixture(abi: &str) -> (Value, BTreeMap<String, Vec<u8>>) {
        let secondary = if abi == "arm64-v8a" {
            "armeabi-v7a"
        } else {
            "x86"
        };
        let mut files = BTreeMap::new();
        for (name, mode) in [
            ("bin/sui", 0o755),
            ("bin/sepolicy_checker", 0o755),
            ("librish.so", 0o644),
            ("zygisk.so", 0o644),
            ("sui.dex", 0o600),
            ("sepolicy.rule", 0o644),
            ("rish", 0o770),
            ("licenses/LICENSE", 0o644),
            ("licenses/UPSTREAM.md", 0o644),
            ("licenses/api_LICENSE", 0o644),
            ("licenses/SOURCE.lock.json", 0o644),
            ("licenses/LUNEXSU.md", 0o644),
        ] {
            files.insert(name.to_owned(), mode);
        }
        files.insert(format!("zygisk.{secondary}.so"), 0o644);
        let mut contents = BTreeMap::new();
        let entries: Vec<_> = files
            .into_iter()
            .map(|(name, mode)| {
                let data = name.as_bytes().to_vec();
                let entry = json!({"name":name, "mode":mode, "sha256":sha256::digest(&data)});
                contents.insert(name, data);
                entry
            })
            .collect();
        (
            json!({"schema":1, "version":"13.5.4.3", "version_code":130504,
            "primary_abi":abi, "secondary_abi":secondary, "files":entries}),
            contents,
        )
    }

    fn check(manifest: &Value, files: &BTreeMap<String, Vec<u8>>, abi: &str) -> bool {
        payload::validate(
            |name| {
                if name == "payload.json" {
                    return Ok(serde_json::to_vec(manifest)?);
                }
                files
                    .get(name)
                    .cloned()
                    .ok_or_else(|| anyhow::anyhow!("Missing {name}"))
            },
            abi,
        )
        .is_ok()
    }

    #[test]
    fn both_bundled_architectures_validate_all_members() {
        for abi in ["arm64-v8a", "x86_64"] {
            let (manifest, files) = fixture(abi);
            assert!(check(&manifest, &files, abi));
        }
    }

    #[cfg(unix)]
    #[test]
    fn publication_is_disabled_owned_and_keeps_adb_policy_out_of_runtime() {
        use super::deployment::{self, MODULE_ID, Paths};
        let root = tempfile::tempdir().unwrap();
        let transactions = root.path().join("transactions");
        let updates = root.path().join("updates");
        let modules = root.path().join("modules");
        let paths = Paths {
            transactions: &transactions,
            updates: &updates,
            modules: &modules,
        };
        let (manifest, files) = fixture("arm64-v8a");
        let read = |name: &str| -> anyhow::Result<Vec<u8>> {
            if name == "payload.json" {
                return Ok(serde_json::to_vec(&manifest)?);
            }
            Ok(files[name].clone())
        };
        deployment::stage_disabled(&paths, read, "arm64-v8a").unwrap();
        let live = modules.join(MODULE_ID);
        let pending = updates.join(MODULE_ID);
        assert!(deployment::owned(&live).unwrap());
        assert!(deployment::owned(&pending).unwrap());
        assert!(live.join("disable").is_file() && live.join("update").is_file());
        assert!(pending.join("disable").is_file());
        assert_eq!(
            std::fs::read(pending.join("zygisk/arm64-v8a.so")).unwrap(),
            files["zygisk.so"]
        );
        assert_eq!(
            std::fs::read(pending.join("zygisk/armeabi-v7a.so")).unwrap(),
            files["zygisk.armeabi-v7a.so"]
        );
        assert_eq!(
            std::fs::read(pending.join("licenses/vendor-sepolicy.rule")).unwrap(),
            files["sepolicy.rule"]
        );
        assert!(
            !std::fs::read_to_string(pending.join("sepolicy.rule"))
                .unwrap()
                .contains("allow ")
        );
        for script in ["post-fs-data.sh", "service.sh", "boot-completed.sh"] {
            assert!(!pending.join(script).exists());
        }
        // A second prepare exchanges only an already-owned, disabled candidate.
        deployment::stage_disabled(&paths, read, "arm64-v8a").unwrap();
        assert!(deployment::owned(&pending).unwrap());
        std::fs::remove_file(live.join("disable")).unwrap();
        assert!(deployment::stage_disabled(&paths, read, "arm64-v8a").is_err());
        assert!(deployment::owned(&pending).unwrap());
        std::fs::remove_file(pending.join("disable")).unwrap();
        // Simulate power/process loss before the activation intent was committed.
        deployment::disable_owned(&paths).unwrap();
        deployment::disable_owned(&paths).unwrap();
        assert!(live.join("disable").is_file());
        assert!(pending.join("disable").is_file());
        assert!(deployment::owned(&live).unwrap() && deployment::owned(&pending).unwrap());
    }

    #[cfg(unix)]
    #[test]
    fn preparation_never_adopts_external_modules_symlinks_or_pending_removal() {
        use super::deployment::{self, MODULE_ID, Paths};
        use std::{fs, os::unix::fs::symlink};
        for case in [
            "external-live",
            "external-pending",
            "directory-link",
            "marker-link",
            "conflicting-id",
        ] {
            let root = tempfile::tempdir().unwrap();
            let transactions = root.path().join("transactions");
            let updates = root.path().join("updates");
            let modules = root.path().join("modules");
            let paths = Paths {
                transactions: &transactions,
                updates: &updates,
                modules: &modules,
            };
            fs::create_dir_all(&modules).unwrap();
            fs::create_dir_all(&updates).unwrap();
            let foreign = root.path().join("foreign");
            fs::create_dir(&foreign).unwrap();
            fs::write(foreign.join("preserve"), b"do not adopt").unwrap();
            match case {
                "external-live" => fs::create_dir(modules.join(MODULE_ID)).unwrap(),
                "external-pending" => fs::create_dir(updates.join(MODULE_ID)).unwrap(),
                "directory-link" => symlink(&foreign, modules.join(MODULE_ID)).unwrap(),
                "marker-link" => {
                    fs::create_dir(modules.join(MODULE_ID)).unwrap();
                    symlink(
                        foreign.join("preserve"),
                        modules.join(MODULE_ID).join(".lunexsu-managed-sui"),
                    )
                    .unwrap();
                }
                _ => fs::create_dir(modules.join("zygisk-sui")).unwrap(),
            }
            let (manifest, files) = fixture("x86_64");
            assert!(
                deployment::stage_disabled(
                    &paths,
                    |name| {
                        if name == "payload.json" {
                            Ok(serde_json::to_vec(&manifest)?)
                        } else {
                            Ok(files[name].clone())
                        }
                    },
                    "x86_64"
                )
                .is_err(),
                "{case}"
            );
            assert_eq!(fs::read(foreign.join("preserve")).unwrap(), b"do not adopt");
            assert!(!updates.join(MODULE_ID).join("payload.json").exists());
        }
    }

    #[cfg(unix)]
    #[test]
    fn failed_metadata_publication_restores_the_previous_pending_candidate() {
        use super::deployment::{self, MODULE_ID, Paths};
        use std::fs;
        let root = tempfile::tempdir().unwrap();
        let transactions = root.path().join("transactions");
        let updates = root.path().join("updates");
        let modules = root.path().join("modules");
        let paths = Paths {
            transactions: &transactions,
            updates: &updates,
            modules: &modules,
        };
        let (manifest, files) = fixture("arm64-v8a");
        let read = |name: &str| -> anyhow::Result<Vec<u8>> {
            if name == "payload.json" {
                return Ok(serde_json::to_vec(&manifest)?);
            }
            Ok(files[name].clone())
        };
        deployment::stage_disabled(&paths, read, "arm64-v8a").unwrap();
        let pending = updates.join(MODULE_ID);
        fs::write(pending.join("keep-previous"), b"previous candidate").unwrap();
        let live = modules.join(MODULE_ID);
        // A malformed metadata target forces failure after publication, before commit.
        fs::remove_file(live.join("update")).unwrap();
        fs::create_dir(live.join("update")).unwrap();
        assert!(deployment::stage_disabled(&paths, read, "arm64-v8a").is_err());
        assert_eq!(
            fs::read(pending.join("keep-previous")).unwrap(),
            b"previous candidate"
        );
        assert!(deployment::owned(&pending).unwrap());
        assert!(live.join("disable").is_file());
        assert!(live.join("update").is_dir());
    }

    #[cfg(unix)]
    #[test]
    fn malformed_payload_never_creates_runtime_directories() {
        use super::deployment::{self, Paths};
        let root = tempfile::tempdir().unwrap();
        let transactions = root.path().join("transactions");
        let updates = root.path().join("updates");
        let modules = root.path().join("modules");
        let paths = Paths {
            transactions: &transactions,
            updates: &updates,
            modules: &modules,
        };
        assert!(
            deployment::stage_disabled(&paths, |_| Ok(b"invalid".to_vec()), "arm64-v8a").is_err()
        );
        assert!(!transactions.exists() && !updates.exists() && !modules.exists());
    }

    #[test]
    fn invalid_paths_duplicates_modes_or_digest_never_reach_member_reader() {
        let (manifest, _) = fixture("arm64-v8a");
        let mut cases = Vec::new();
        for name in [
            "../outside",
            "/absolute",
            "bin\\sui",
            "bin/sui/../sui",
            "new.sh",
        ] {
            let mut m = manifest.clone();
            m["files"][0]["name"] = json!(name);
            cases.push(m);
        }
        let mut duplicate = manifest.clone();
        duplicate["files"][0] = duplicate["files"][1].clone();
        cases.push(duplicate);
        let mut mode = manifest.clone();
        mode["files"][0]["mode"] = json!(0o777);
        cases.push(mode);
        let mut digest = manifest.clone();
        digest["files"][0]["sha256"] = json!("A".repeat(64));
        cases.push(digest);
        for invalid in cases {
            let reads = RefCell::new(Vec::new());
            let result = payload::validate(
                |name| {
                    reads.borrow_mut().push(name.to_owned());
                    Ok(serde_json::to_vec(&invalid)?)
                },
                "arm64-v8a",
            );
            assert!(result.is_err());
            assert_eq!(&*reads.borrow(), &["payload.json"]);
        }
    }

    #[test]
    fn every_member_is_required_and_hash_checked() {
        let (manifest, files) = fixture("x86_64");
        for name in files.keys() {
            let mut missing = files.clone();
            missing.remove(name);
            assert!(!check(&manifest, &missing, "x86_64"));
            let mut corrupt = files.clone();
            corrupt.insert(name.clone(), b"changed".to_vec());
            assert!(!check(&manifest, &corrupt, "x86_64"));
            let mut empty = files.clone();
            empty.insert(name.clone(), Vec::new());
            assert!(!check(&manifest, &empty, "x86_64"));
        }
    }

    #[test]
    fn schemas_architectures_versions_and_unknown_keys_fail_closed() {
        let (manifest, files) = fixture("arm64-v8a");
        for (key, value) in [
            ("schema", json!(2)),
            ("version", json!("")),
            ("version", json!("a b")),
            ("version_code", json!(0)),
            ("version_code", json!(u32::MAX)),
            ("primary_abi", json!("x86_64")),
            ("secondary_abi", json!("x86")),
            ("unknown", json!(true)),
        ] {
            let mut bad = manifest.clone();
            bad[key] = value;
            assert!(!check(&bad, &files, "arm64-v8a"));
        }
        assert!(!check(&manifest, &files, "riscv64"));
        let mut missing = manifest.clone();
        missing["files"].as_array_mut().unwrap().pop();
        assert!(!check(&missing, &files, "arm64-v8a"));
    }

    #[test]
    fn malformed_and_duplicate_json_fields_are_rejected() {
        let (manifest, files) = fixture("arm64-v8a");
        let text = serde_json::to_string(&manifest).unwrap();
        let mut unknown = manifest.clone();
        unknown["files"][0]["unknown"] = json!(true);
        let cases = [
            text.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
            text.replacen("\"mode\":493", "\"mode\":493,\"mode\":493", 1),
            serde_json::to_string(&unknown).unwrap(),
            "{".to_owned(),
        ];
        for invalid in cases {
            let result = payload::validate(
                |name| {
                    if name == "payload.json" {
                        return Ok(invalid.as_bytes().to_vec());
                    }
                    Ok(files[name].clone())
                },
                "arm64-v8a",
            );
            assert!(result.is_err());
        }
    }

    #[test]
    fn oversized_manifests_and_members_are_rejected() {
        let result = payload::validate(|_| Ok(vec![b' '; 64 * 1024 + 1]), "arm64-v8a");
        assert!(format!("{:#}", result.unwrap_err()).contains("Oversized Sui manifest"));
        let (manifest, _) = fixture("arm64-v8a");
        let result = payload::validate(
            |name| {
                if name == "payload.json" {
                    return Ok(serde_json::to_vec(&manifest)?);
                }
                Ok(vec![0; 16 * 1024 * 1024 + 1])
            },
            "arm64-v8a",
        );
        assert!(format!("{:#}", result.unwrap_err()).contains("Invalid Sui member size"));
    }

    #[test]
    fn total_payload_is_bounded_even_when_individual_files_are_valid() {
        let (mut manifest, _) = fixture("arm64-v8a");
        let block = vec![1; 3 * 1024 * 1024];
        let digest = sha256::digest(&block);
        for entry in manifest["files"].as_array_mut().unwrap() {
            entry["sha256"] = json!(digest);
        }
        let result = payload::validate(
            |name| {
                if name == "payload.json" {
                    return Ok(serde_json::to_vec(&manifest)?);
                }
                Ok(block.clone())
            },
            "arm64-v8a",
        );
        assert!(format!("{:#}", result.unwrap_err()).contains("Oversized Sui payload"));
    }

    #[test]
    #[ignore = "requires a separately built and staged Sui artifact; only reads its files"]
    fn actual_bundled_payload_can_be_checked_without_executing_it() {
        let root = std::env::var("LUNEXSU_SUI_PAYLOADS")
            .expect("Set LUNEXSU_SUI_PAYLOADS for the explicit real-payload check");
        for (directory, abi) in [("aarch64", "arm64-v8a"), ("x86_64", "x86_64")] {
            let base = std::path::Path::new(&root).join(directory).join("sui");
            let manifest =
                payload::validate(|name| Ok(std::fs::read(base.join(name))?), abi).unwrap();
            assert_eq!(manifest.version, "13.5.4.3");
            assert_eq!(manifest.version_code, 130504);
            assert_eq!(manifest.primary_abi, abi);
            assert_eq!(manifest.files.len(), 13);
        }
    }
}
#[cfg(test)]
#[path = "../../../src/sui/state.rs"]
mod state;
#[cfg(test)]
mod state_tests;
