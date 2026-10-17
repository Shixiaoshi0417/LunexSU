//! Standalone host tests: rustc --edition=2024 --test host.rs -o <D-cache>/tests
//! No Android commands, device access, real properties, or partitions.
#[path = "../../userspace/ksud/src/boot_lifecycle.rs"]
mod boot;
#[path = "../../userspace/ksud/src/magica_state.rs"]
mod magica;

use std::cell::{Cell, RefCell};
use std::collections::{BTreeMap, BTreeSet, VecDeque};
use std::io::{self, Cursor};
use std::rc::Rc;

#[derive(Clone, Debug, PartialEq, Eq)]
struct SavedState {
    properties: BTreeMap<String, String>,
    permissions: BTreeMap<String, u32>,
}

struct FakeState {
    state: SavedState,
    reads: usize,
    mutations: usize,
    restart_count: usize,
    fail_read: Option<usize>,
    fail_mutations: BTreeSet<usize>,
    calls: Vec<String>,
}

impl FakeState {
    fn new() -> Self {
        Self {
            state: SavedState {
                properties: [
                    ("ro.debuggable", "original-debug"),
                    ("ro.adb.secure", "original-auth"),
                    ("service.adb.root", ""),
                    ("ro.boot.selinux", "leave-me-alone"),
                ]
                .into_iter()
                .map(|(k, v)| (k.to_owned(), v.to_owned()))
                .collect(),
                permissions: [("serial".to_owned(), 0o440), ("context".to_owned(), 0o640)]
                    .into_iter()
                    .collect(),
            },
            reads: 0,
            mutations: 0,
            restart_count: 0,
            fail_read: None,
            fail_mutations: BTreeSet::new(),
            calls: Vec::new(),
        }
    }

    fn read(&mut self) -> io::Result<()> {
        self.reads += 1;
        if self.fail_read == Some(self.reads) {
            return Err(io::Error::other("injected snapshot error"));
        }
        Ok(())
    }

    fn mutation(&mut self, name: String) -> io::Result<()> {
        self.calls.push(name);
        self.mutations += 1;
        // Fail AFTER applying the operation to model a partial/error result.
        if self.fail_mutations.remove(&self.mutations) {
            return Err(io::Error::other("injected mutation error"));
        }
        Ok(())
    }
}

#[derive(Clone)]
struct FakeAdb(Rc<RefCell<FakeState>>);

impl magica::AdbState for FakeAdb {
    fn property(&mut self, name: &str) -> io::Result<Option<String>> {
        let mut fake = self.0.borrow_mut();
        fake.read()?;
        Ok(fake.state.properties.get(name).cloned())
    }

    fn permission_files(&mut self) -> io::Result<Vec<String>> {
        self.0.borrow_mut().read()?;
        // Duplicate contexts are common; capture/restore each unique file once.
        Ok(vec![
            "serial".to_owned(),
            "context".to_owned(),
            "context".to_owned(),
        ])
    }

    fn mode(&mut self, path: &str) -> io::Result<u32> {
        let mut fake = self.0.borrow_mut();
        fake.read()?;
        Ok(fake.state.permissions[path])
    }

    fn chmod(&mut self, path: &str, mode: u32) -> io::Result<()> {
        let mut fake = self.0.borrow_mut();
        fake.state.permissions.insert(path.to_owned(), mode);
        fake.mutation(format!("chmod {path} {mode:o}"))
    }

    fn set_direct(&mut self, name: &str, value: Option<&str>) -> io::Result<()> {
        let mut fake = self.0.borrow_mut();
        if let Some(value) = value {
            fake.state
                .properties
                .insert(name.to_owned(), value.to_owned());
        } else {
            fake.state.properties.remove(name);
        }
        fake.mutation(format!("direct {name}"))
    }

    fn set_service(&mut self, name: &str, value: &str) -> io::Result<()> {
        let mut fake = self.0.borrow_mut();
        fake.state
            .properties
            .insert(name.to_owned(), value.to_owned());
        fake.mutation(format!("service {name}"))
    }

    fn restart(&mut self) -> io::Result<()> {
        let mut fake = self.0.borrow_mut();
        fake.restart_count += 1;
        fake.mutation("restart".to_owned())
    }
}

fn fake_adb() -> (FakeAdb, SavedState) {
    let fake = FakeAdb(Rc::new(RefCell::new(FakeState::new())));
    let original = fake.0.borrow().state.clone();
    (fake, original)
}

#[test]
fn magica_success_restores_exact_values_absence_empty_and_permissions() {
    let (fake, original) = fake_adb();
    magica::with_temporary_adb(fake.clone(), 5555, || {
        let active = fake.0.borrow();
        assert_eq!(active.state.properties["ro.debuggable"], "1");
        assert_eq!(active.state.properties["ro.adb.secure"], "0");
        assert_eq!(active.state.properties["service.adb.root"], "1");
        assert_eq!(active.state.properties["service.adb.tcp.port"], "5555");
        assert_eq!(active.state.permissions, original.permissions);
        Ok(())
    })
    .unwrap();
    assert_eq!(fake.0.borrow().state, original);
    assert_eq!(fake.0.borrow().restart_count, 2);
}

#[test]
fn magica_preserves_preexisting_root_and_tcp_configuration() {
    let (fake, _) = fake_adb();
    fake.0
        .borrow_mut()
        .state
        .properties
        .insert("service.adb.root".to_owned(), "1".to_owned());
    fake.0
        .borrow_mut()
        .state
        .properties
        .insert("service.adb.tcp.port".to_owned(), "4321".to_owned());
    let original = fake.0.borrow().state.clone();
    magica::with_temporary_adb(fake.clone(), 5555, || Ok(())).unwrap();
    assert_eq!(fake.0.borrow().state, original);
}

#[test]
fn magica_snapshot_failures_make_no_changes() {
    for fail_read in 1..=7 {
        let (fake, original) = fake_adb();
        fake.0.borrow_mut().fail_read = Some(fail_read);
        let result: io::Result<()> =
            magica::with_temporary_adb(fake.clone(), 5555, || panic!("dispatch called"));
        assert!(result.is_err(), "snapshot read {fail_read}");
        assert_eq!(fake.0.borrow().mutations, 0);
        assert_eq!(fake.0.borrow().state, original);
    }
}

#[test]
fn magica_every_partial_enable_failure_restores_state() {
    for mutation in 1..=9 {
        let (fake, original) = fake_adb();
        fake.0.borrow_mut().fail_mutations.insert(mutation);
        let result: io::Result<()> =
            magica::with_temporary_adb(fake.clone(), 5555, || panic!("dispatch called"));
        assert!(result.is_err(), "enable mutation {mutation}");
        assert_eq!(
            fake.0.borrow().state,
            original,
            "enable mutation {mutation}"
        );
        assert!(
            fake.0
                .borrow()
                .calls
                .last()
                .is_some_and(|call| call == "restart")
        );
    }
}

#[test]
fn magica_connect_dispatch_and_child_failures_restore_state() {
    for failure in ["connect failed", "dispatch failed", "child failed"] {
        let (fake, original) = fake_adb();
        let result: io::Result<()> =
            magica::with_temporary_adb(fake.clone(), 5555, || Err(io::Error::other(failure)));
        assert!(result.unwrap_err().to_string().contains(failure));
        assert_eq!(fake.0.borrow().state, original);
    }
}

#[test]
fn magica_restore_errors_are_reported_and_all_cleanup_is_attempted() {
    let (fake, original) = fake_adb();
    // Enable has nine mutations. Fail multiple independent restoration steps.
    fake.0.borrow_mut().fail_mutations.extend([10, 13, 16, 18]);
    let result: io::Result<()> = magica::with_temporary_adb(fake.clone(), 5555, || {
        Err(io::Error::other("dispatch failed"))
    });
    let error = result.unwrap_err().to_string();
    assert!(error.contains("dispatch failed"));
    assert!(error.contains("Magica restoration also failed"));
    assert!(error.contains("writable"));
    assert!(error.contains("ro.adb.secure"));
    assert!(error.contains("permissions"));
    assert!(error.contains("restart restored adbd"));
    // The Drop fallback retries after the explicitly reported restore failure.
    assert_eq!(fake.0.borrow().state, original);
    assert_eq!(fake.0.borrow().restart_count, 3);
}

#[test]
fn magica_success_cannot_hide_restoration_failure() {
    let (fake, _) = fake_adb();
    fake.0.borrow_mut().fail_mutations.insert(18);
    let error = magica::with_temporary_adb(fake, 5555, || Ok(())).unwrap_err();
    assert!(error.to_string().contains("Magica restoration failed"));
}

#[test]
fn magica_guard_restores_on_unwind() {
    let (fake, original) = fake_adb();
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let _: io::Result<()> =
            magica::with_temporary_adb(fake.clone(), 5555, || panic!("child panic"));
    }));
    assert!(result.is_err());
    assert_eq!(fake.0.borrow().state, original);
}

#[test]
fn magica_rejects_zero_port_without_even_reading_properties() {
    let (fake, original) = fake_adb();
    assert!(magica::with_temporary_adb(fake.clone(), 0, || Ok(())).is_err());
    assert_eq!(fake.0.borrow().reads, 0);
    assert_eq!(fake.0.borrow().state, original);
}

#[test]
fn magica_checks_missing_nonzero_and_successful_shell_statuses() {
    assert!(magica::check_dispatch_status(b"no status\n").is_err());
    assert!(magica::check_dispatch_status(b"LUNEXSU_MAGICA_DISPATCH_STATUS=1\n").is_err());
    assert!(magica::check_dispatch_status(b"LUNEXSU_MAGICA_DISPATCH_STATUS=\n").is_err());
    magica::check_dispatch_status(b"logs\nLUNEXSU_MAGICA_DISPATCH_STATUS=0\r\n").unwrap();
}

#[test]
fn magica_waits_for_daemon_completion_instead_of_only_dispatch() {
    let (fake, original) = fake_adb();
    let reads = Cell::new(0);
    let waits = Cell::new(0);
    magica::with_temporary_adb(fake.clone(), 5555, || {
        magica::wait_for_child(
            3,
            || {
                reads.set(reads.get() + 1);
                Ok((reads.get() == 3).then(|| b"OK\n".to_vec()))
            },
            || waits.set(waits.get() + 1),
        )
    })
    .unwrap();
    assert_eq!(reads.get(), 3);
    assert_eq!(waits.get(), 2);
    assert_eq!(fake.0.borrow().state, original);
}

#[test]
fn magica_child_error_malformed_report_and_timeout_restore_state() {
    for report in [
        None,
        Some(b"ERROR\nmodule load failed".to_vec()),
        Some(b"broken".to_vec()),
        Some(vec![255]),
    ] {
        let (fake, original) = fake_adb();
        let result = magica::with_temporary_adb(fake.clone(), 5555, || {
            magica::wait_for_child(2, || Ok(report.clone()), || {})
        });
        assert!(result.is_err());
        assert_eq!(fake.0.borrow().state, original);
    }
    let (fake, original) = fake_adb();
    assert!(
        magica::with_temporary_adb(fake.clone(), 5555, || {
            magica::wait_for_child(2, || Err(io::Error::other("completion file denied")), || {})
        })
        .is_err()
    );
    assert_eq!(fake.0.borrow().state, original);
}

#[test]
fn magica_shell_arguments_do_not_allow_expansion_or_injection() {
    assert_eq!(
        magica::shell_quote("a'b;$(touch NO)"),
        "'a'\\''b;$(touch NO)'"
    );
    assert_eq!(magica::shell_quote(""), "''");
    assert_eq!(magica::STATUS_MARKER, "LUNEXSU_MAGICA_DISPATCH_STATUS=");
}

struct FakeBootctl {
    results: VecDeque<io::Result<boot::BootctlOutput>>,
    calls: Vec<Vec<String>>,
}

impl boot::Bootctl for FakeBootctl {
    fn run(&mut self, args: &[&str]) -> io::Result<boot::BootctlOutput> {
        self.calls
            .push(args.iter().map(|arg| (*arg).to_owned()).collect());
        self.results
            .pop_front()
            .expect("unexpected bootctl command")
    }
}

fn output(success: bool, stdout: &[u8]) -> boot::BootctlOutput {
    boot::BootctlOutput {
        success,
        stdout: stdout.to_vec(),
    }
}

#[test]
fn ota_passes_separate_argv_for_each_valid_slot() {
    for (suffix, current, target) in [
        ("_a", b"0\n".as_slice(), "1"),
        ("_b", b"1\r\n".as_slice(), "0"),
    ] {
        let mut fake = FakeBootctl {
            results: [
                Ok(output(true, b"")),
                Ok(output(true, current)),
                Ok(output(true, b"")),
            ]
            .into(),
            calls: vec![],
        };
        let plan = boot::prepare_ota(&mut fake, suffix).unwrap();
        boot::activate_ota(&mut fake, &plan).unwrap();
        assert_eq!(
            fake.calls,
            vec![
                vec!["hal-info"],
                vec!["get-current-slot"],
                vec!["set-active-boot-slot", target]
            ]
        );
    }
}

#[test]
fn ota_invalid_suffixes_do_not_launch_any_process() {
    for suffix in ["", "_c", "a", "_a\n", "_a;reboot"] {
        let mut fake = FakeBootctl {
            results: VecDeque::new(),
            calls: vec![],
        };
        assert!(boot::prepare_ota(&mut fake, suffix).is_err());
        assert_eq!(fake.calls.len(), 0);
    }
    assert_eq!(boot::other_slot_suffix("_a").unwrap(), "_b");
    assert_eq!(boot::other_slot_suffix("_b").unwrap(), "_a");
}

#[test]
fn ota_rejects_empty_malformed_non_ab_and_inconsistent_current_slot() {
    for current in [
        b"".as_slice(),
        b"2",
        b"-1",
        b"01",
        b"0\n1",
        b"abc",
        b"\xff",
        b"1",
    ] {
        let mut fake = FakeBootctl {
            results: [Ok(output(true, b"")), Ok(output(true, current))].into(),
            calls: vec![],
        };
        assert!(boot::prepare_ota(&mut fake, "_a").is_err(), "{current:?}");
        assert_eq!(fake.calls.len(), 2);
    }
}

#[test]
fn ota_checks_every_process_spawn_and_exit_status_before_next_step() {
    for failed_step in 0..3 {
        for spawn_error in [false, true] {
            let mut responses = vec![
                Ok(output(true, b"")),
                Ok(output(true, b"0")),
                Ok(output(true, b"")),
            ];
            responses[failed_step] = if spawn_error {
                Err(io::Error::other("injected fake process spawn failure"))
            } else {
                Ok(output(false, b"0"))
            };
            let mut fake = FakeBootctl {
                results: responses.into(),
                calls: vec![],
            };
            let script_written = Cell::new(false);
            let result = boot::prepare_ota(&mut fake, "_a").and_then(|plan| {
                boot::activate_ota(&mut fake, &plan)?;
                script_written.set(true);
                Ok(())
            });
            assert!(result.is_err());
            assert_eq!(fake.calls.len(), failed_step + 1);
            assert!(!script_written.get());
        }
    }
}

#[test]
fn uninstall_preserves_backups_modules_and_daemon_on_every_restore_failure() {
    for failed_stage in [
        "prepare",
        "backup-read",
        "fallback-rebuild",
        "flash-write",
        "sync",
        "read-back",
    ] {
        let files = RefCell::new(vec!["backup", "modules", "daemon"]);
        let events = RefCell::new(Vec::new());
        let result: io::Result<()> = boot::restore_before_cleanup(
            || {
                events.borrow_mut().push("restore");
                Err(io::Error::other(failed_stage))
            },
            || {
                events.borrow_mut().push("cleanup");
                files.borrow_mut().clear();
                Ok(())
            },
        );
        assert!(result.is_err());
        assert_eq!(*files.borrow(), vec!["backup", "modules", "daemon"]);
        assert_eq!(*events.borrow(), vec!["restore"]);
    }
}

#[test]
fn uninstall_cleans_up_only_after_verified_restore() {
    let events = RefCell::new(Vec::new());
    boot::restore_before_cleanup(
        || {
            events.borrow_mut().push("restore");
            boot::verify_restored_image(Cursor::new(b"stock boot"), b"stock boot")
        },
        || {
            events.borrow_mut().push("cleanup");
            Ok(())
        },
    )
    .unwrap();
    assert_eq!(*events.borrow(), vec!["restore", "cleanup"]);
}

#[test]
fn uninstall_readback_mismatch_or_short_read_prevents_cleanup() {
    for bytes in [b"stock boof".as_slice(), b"stock"] {
        let cleaned = Cell::new(false);
        let result = boot::restore_before_cleanup(
            || boot::verify_restored_image(Cursor::new(bytes), b"stock boot"),
            || {
                cleaned.set(true);
                Ok(())
            },
        );
        assert!(result.is_err());
        assert!(!cleaned.get());
    }
}

#[test]
fn readback_handles_multiple_chunks_and_propagates_io_failures() {
    struct BrokenReader;
    impl io::Read for BrokenReader {
        fn read(&mut self, _: &mut [u8]) -> io::Result<usize> {
            Err(io::Error::other("injected read failure"))
        }
    }
    let expected = vec![42_u8; 20000];
    boot::verify_restored_image(Cursor::new(&expected), &expected).unwrap();
    assert!(boot::verify_restored_image(BrokenReader, b"stock boot").is_err());
}

#[test]
fn android_wiring_keeps_fallback_and_defers_backup_cleanup() {
    let source = include_str!("../../userspace/ksud/src/boot_patch.rs");
    let restore = source.split("pub fn restore(args:").nth(1).unwrap();
    assert!(restore.contains("rebuild_without_ksu(&boot_image, &mut cpio, vendor_ramdisk_idx)?"));
    assert!(
        restore.find("verify_restored_image").unwrap()
            < restore.find("clean_backup(&sha)").unwrap()
    );
    assert!(
        restore.find("write(&output_image").unwrap() < restore.find("clean_backup(&sha)").unwrap()
    );
    assert!(source.contains("mark-boot-successful || exit $?"));
    assert!(!source.contains(".arg(format!(\"set-active-boot-slot"));
    let utils = include_str!("../../userspace/ksud/src/utils.rs");
    let uninstall = utils
        .split("pub fn uninstall(package_name:")
        .nth(1)
        .unwrap();
    assert!(
        uninstall.find("restore_before_cleanup").unwrap()
            < uninstall.find("remove_dir_all(defs::WORKING_DIR)").unwrap()
    );
    let magica_source = include_str!("../../userspace/ksud/src/magica.rs");
    assert!(magica_source.contains("state::with_temporary_adb"));
    assert!(magica_source.contains("state::wait_for_child"));
    assert!(magica_source.contains("may listen on ALL interfaces"));
    assert!(
        magica_source.find("allow_network,").unwrap()
            < magica_source.find("sys_prop::init()").unwrap()
    );
}
