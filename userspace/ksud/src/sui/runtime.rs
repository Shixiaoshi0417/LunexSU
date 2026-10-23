//! Explicit, reboot-gated lifecycle. No vendor boot scripts or ADB-root helpers are run.
use super::{deployment, payload, state, supervision};
use anyhow::{Context, Result, ensure};
use std::{
    fs::{self, File, OpenOptions},
    io::Read,
    os::unix::{
        fs::{OpenOptionsExt, PermissionsExt},
        process::CommandExt,
    },
    path::{Path, PathBuf},
    process::{Child, Command, Output, Stdio},
    thread,
    time::{Duration, Instant},
};

pub fn directory() -> Result<PathBuf> {
    let path = Path::new(crate::defs::WORKING_DIR).join("lunexsu-sui");
    fs::create_dir_all(&path)?;
    ensure!(
        fs::symlink_metadata(&path)?.is_dir(),
        "Invalid Sui state directory"
    );
    fs::set_permissions(&path, fs::Permissions::from_mode(0o700))?;
    Ok(path)
}

fn boot() -> Result<String> {
    let value = fs::read_to_string("/proc/sys/kernel/random/boot_id")?
        .trim()
        .to_owned();
    ensure!(state::valid_boot_id(&value), "Invalid boot identifier");
    Ok(value)
}

fn bounded_read(path: &Path, limit: u64) -> Result<Vec<u8>> {
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(path)?;
    ensure!(file.metadata()?.is_file(), "Not a regular Sui file");
    let mut data = Vec::new();
    file.take(limit + 1).read_to_end(&mut data)?;
    ensure!(data.len() as u64 <= limit, "Oversized Sui file");
    Ok(data)
}

fn desired() -> Result<state::State> {
    let path = Path::new(crate::defs::WORKING_DIR).join("lunexsu-sui/state.json");
    if !deployment::exists(&path)? {
        return Ok(state::State::default());
    }
    state::State::parse(&bounded_read(&path, 4096)?)
}

fn live() -> PathBuf {
    Path::new(crate::defs::MODULE_DIR).join(deployment::MODULE_ID)
}
fn pending() -> PathBuf {
    Path::new(crate::defs::MODULE_UPDATE_DIR).join(deployment::MODULE_ID)
}

fn check_module(path: &Path) -> Result<()> {
    ensure!(
        deployment::owned(path)?,
        "Sui installation is not owned by LunexSU"
    );
    ensure!(
        !deployment::exists(&path.join("remove"))?,
        "Sui removal is pending"
    );
    let abi = if std::env::consts::ARCH == "aarch64" {
        "arm64-v8a"
    } else {
        std::env::consts::ARCH
    };
    payload::validate(
        |name| {
            let relative = if name == "zygisk.so" {
                format!("zygisk/{abi}.so")
            } else if name == "sepolicy.rule" {
                "licenses/vendor-sepolicy.rule".to_owned()
            } else if let Some(secondary) = name
                .strip_prefix("zygisk.")
                .and_then(|s| s.strip_suffix(".so"))
            {
                format!("zygisk/{secondary}.so")
            } else {
                name.to_owned()
            };
            bounded_read(&path.join(relative), 16 * 1024 * 1024)
        },
        abi,
    )?;
    Ok(())
}

fn provider() -> Result<()> {
    // Presence is only a startup prerequisite, never evidence that injection succeeded.
    let ready = ["rezygisk", "zygisksu"]
        .iter()
        .filter(|id| {
            let path = Path::new(crate::defs::MODULE_DIR).join(id);
            fs::symlink_metadata(&path).is_ok_and(|m| m.is_dir())
                && ["disable", "remove", "update"]
                    .iter()
                    .all(|flag| !path.join(flag).exists())
                && !Path::new(crate::defs::MODULE_UPDATE_DIR).join(id).exists()
        })
        .count();
    ensure!(
        ready == 1,
        "Enable exactly one ReZygisk or NeoZygisk provider and reboot first"
    );
    Ok(())
}

fn private_data(create: bool) -> Result<()> {
    const MARKER: &str = ".lunexsu-managed-sui";
    const CONTENT: &[u8] = b"lunexsu-managed-sui:1\n";
    let path = Path::new("/data/adb/sui");
    if deployment::exists(path)? {
        ensure!(
            fs::symlink_metadata(path)?.is_dir(),
            "Invalid Sui data path"
        );
        ensure!(
            bounded_read(&path.join(MARKER), 128)? == CONTENT,
            "External Sui data requires an explicit migration; refusing to adopt it"
        );
    } else {
        ensure!(create, "Managed Sui data is missing");
        fs::create_dir(path)?;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
        deployment::write_file(&path.join(MARKER), CONTENT, 0o600)?;
        File::open(path)?.sync_all()?;
        File::open(path.parent().context("Missing Sui data parent")?)?.sync_all()?;
    }
    Ok(())
}

pub fn set_enabled(enabled: bool) -> Result<()> {
    ensure!(
        unsafe { libc::geteuid() } == 0,
        "Sui lifecycle requires root"
    );
    let dir = directory()?;
    let _operation = crate::file_lock::FileLock::exclusive(&dir.join("operation.lock"))?;
    // Corrupt intent must not prevent an explicit stop, but can never authorize activation.
    let previous = if enabled {
        desired()?
    } else {
        desired().unwrap_or_default()
    };
    let next = previous.request(enabled, &boot()?)?;
    if !enabled {
        // Stop the supervisor even if an unrelated module installer currently owns the module lock.
        deployment::write_file(&dir.join("state.json"), &serde_json::to_vec(&next)?, 0o600)?;
        File::open(&dir)?.sync_all()?;
        File::open(Path::new(crate::defs::WORKING_DIR))?.sync_all()?;
    }
    let root = Path::new(crate::defs::WORKING_DIR).join("module_transactions");
    let _store = crate::module::transaction::Store::open(
        &root,
        Path::new(crate::defs::MODULE_UPDATE_DIR),
        Path::new(crate::defs::MODULE_DIR),
    )?;
    let paths = deployment::Paths {
        transactions: &root,
        updates: Path::new(crate::defs::MODULE_UPDATE_DIR),
        modules: Path::new(crate::defs::MODULE_DIR),
    };
    if enabled {
        crate::ksucalls::ensure_uapi_version_matched()?;
        deployment::check_conflicts(&paths)?;
        ensure!(
            !crate::utils::is_safe_mode(),
            "Sui cannot be enabled in safe mode"
        );
        provider()?;
        let context = fs::read_to_string("/proc/self/attr/current")?;
        ensure!(
            context.trim_matches(['\0', '\n']) == "u:r:ksu:s0",
            "Unsupported root SELinux domain"
        );
        super::register_manager()?;
        let payload_path = if pending().exists() {
            pending()
        } else {
            live()
        };
        check_module(&payload_path)?;
        private_data(true)?;
    }
    for path in [live(), pending()] {
        if !deployment::exists(&path)? {
            continue;
        }
        ensure!(
            deployment::owned(&path)?,
            "Refusing to change an external Sui module"
        );
        if enabled {
            deployment::write_file(&path.join("sepolicy.rule"), state::POLICY, 0o644)?;
            let disable = path.join("disable");
            if deployment::exists(&disable)? {
                fs::remove_file(disable)?;
            }
        } else {
            deployment::write_file(&path.join("disable"), b"", 0o644)?;
        }
        File::open(&path)?.sync_all()?;
    }
    if enabled {
        deployment::write_file(&dir.join("state.json"), &serde_json::to_vec(&next)?, 0o600)?;
        File::open(&dir)?.sync_all()?;
    }
    File::open(Path::new(crate::defs::WORKING_DIR))?.sync_all()?;
    println!(
        "{}",
        serde_json::json!({"schema_version":1,"enabled":enabled,"requires_reboot":true})
    );
    Ok(())
}

fn can_start(check_safemode: bool) -> Result<()> {
    if check_safemode {
        ensure!(
            !crate::utils::has_magisk(),
            "Another root implementation is present"
        );
        ensure!(!crate::utils::is_safe_mode(), "Safe mode");
    }
    ensure!(
        desired()?.after_reboot(&boot()?),
        "Sui is disabled or awaiting reboot"
    );
    let root = Path::new(crate::defs::WORKING_DIR).join("module_transactions");
    deployment::check_conflicts(&deployment::Paths {
        transactions: &root,
        modules: Path::new(crate::defs::MODULE_DIR),
        updates: Path::new(crate::defs::MODULE_UPDATE_DIR),
    })?;
    ensure!(!pending().exists(), "Sui update is pending");
    ensure!(
        deployment::owned(&live())?,
        "Managed Sui module is missing or unowned"
    );
    for flag in ["disable", "remove", "update"] {
        ensure!(
            !deployment::exists(&live().join(flag))?,
            "Sui module is disabled or pending"
        );
    }
    provider()?;
    private_data(false)
}

pub(super) fn wait_output(command: &mut Command, seconds: u64) -> Result<Output> {
    let dir = Path::new(crate::defs::WORKING_DIR).join("lunexsu-sui");
    super::process_helper::wait_output(command, &dir, Duration::from_secs(seconds))
}

fn app_process(class: &str) -> Command {
    let mut command = Command::new("/system/bin/app_process");
    command
        .env("CLASSPATH", live().join("sui.dex"))
        .arg("/system/bin")
        .arg(class);
    command
}

fn launch() -> Result<Child> {
    can_start(true)?;
    check_module(&live())?;
    super::register_manager()?;
    let output = wait_output(app_process("rikka.sui.installer.Installer").arg(live()), 15)?;
    ensure!(
        output.status.success(),
        "Sui package metadata initialization failed"
    );
    let mut command = Command::new(live().join("bin/sui"));
    command
        .arg(live())
        .env("SUI_FOREGROUND", "1")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .process_group(0);
    // Kill the root server if its supervisor unexpectedly dies; shell has its own parent-death guard.
    let parent = std::process::id();
    unsafe {
        command.pre_exec(move || {
            if libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL) != 0
                || libc::getppid() != parent as libc::pid_t
            {
                return Err(std::io::Error::other(
                    "Cannot establish Sui parent lifetime",
                ));
            }
            Ok(())
        });
    }
    Ok(command.spawn()?)
}

pub fn supervise() -> Result<()> {
    ensure!(
        unsafe { libc::geteuid() } == 0,
        "Sui supervision requires root"
    );
    crate::ksucalls::ensure_uapi_version_matched()?;
    let Some(_lock) =
        crate::file_lock::FileLock::try_exclusive(&directory()?.join("supervisor.lock"))?
    else {
        return Ok(());
    };
    let mut failures = 0_u32;
    while can_start(true).is_ok() && failures < 5 {
        let mut child = match launch() {
            Ok(child) => child,
            Err(error) => {
                log::warn!("Sui launch failed: {error:#}");
                let message: String = format!("{error:#}").chars().take(1024).collect();
                let directory = directory()?;
                deployment::write_file(&directory.join("last_error"), message.as_bytes(), 0o600)?;
                File::open(directory)?.sync_all()?;
                failures += 1;
                thread::sleep(Duration::from_secs(1_u64 << failures));
                continue;
            }
        };
        let mut refresh = Instant::now();
        let outcome = supervision::watch(
            &mut child,
            || {
                if can_start(false).is_err() {
                    return false;
                }
                if refresh.elapsed() >= Duration::from_secs(30) {
                    if super::register_manager().is_err() {
                        return false;
                    }
                    refresh = Instant::now();
                }
                true
            },
            || binder_diagnostics().is_ok_and(|value| supervision::servers_alive(&value)),
        )?;
        if outcome == supervision::Outcome::Stopped {
            return Ok(());
        }
        log::warn!("Sui generation stopped: {outcome:?}; retrying within the restart budget");
        failures += 1;
        thread::sleep(Duration::from_secs(1_u64 << failures));
    }
    Ok(())
}

pub fn boot_services() {
    if can_start(true).is_err() {
        return;
    }
    if let Err(error) = Command::new(crate::defs::DAEMON_PATH)
        .args(["sui", "supervise"])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .process_group(0)
        .spawn()
    {
        log::warn!("Cannot start managed Sui supervisor: {error:#}");
    }
}

pub fn reconcile_boot() -> Result<()> {
    if !deployment::exists(&live())? && !deployment::exists(&pending())? {
        return Ok(());
    }
    let dir = directory()?;
    let _operation = crate::file_lock::FileLock::exclusive(&dir.join("operation.lock"))?;
    let transactions = Path::new(crate::defs::WORKING_DIR).join("module_transactions");
    let paths = deployment::Paths {
        transactions: &transactions,
        updates: Path::new(crate::defs::MODULE_UPDATE_DIR),
        modules: Path::new(crate::defs::MODULE_DIR),
    };
    // A crash may have removed disable markers before the enabled intent was durably committed.
    // Never let that partial activation become injection on the next boot.
    if desired().is_ok_and(|value| value.enabled)
        && deployment::check_conflicts(&paths).is_ok()
        && private_data(false).is_ok()
        && check_module(&live()).is_ok()
    {
        return Ok(());
    }
    deployment::disable_owned(&paths)
}

fn binder_diagnostics() -> Result<serde_json::Value> {
    let record = Path::new(crate::defs::WORKING_DIR).join("lunexsu-sui/manager.json");
    let manager: super::identity::Manager = serde_json::from_slice(&bounded_read(&record, 4096)?)?;
    let output = wait_output(
        app_process("rikka.sui.server.SuiDiagnostics").arg(manager.uid.to_string()),
        5,
    )?;
    ensure!(output.status.success(), "Sui Binder diagnostics failed");
    Ok(serde_json::from_slice(&output.stdout)?)
}

pub fn status() -> Result<serde_json::Value> {
    let desired = desired()?;
    let pending_reboot = desired.enabled && (!desired.after_reboot(&boot()?) || pending().exists());
    let bridge = if can_start(true).is_ok() {
        binder_diagnostics().unwrap_or_default()
    } else {
        serde_json::Value::Null
    };
    let healthy = supervision::servers_alive(&bridge)
        && bridge["bridge"]["manager_verified"].as_bool() == Some(true);
    let reason = if desired.enabled && !pending_reboot && !healthy {
        can_start(true).err().map_or_else(
            || {
                bounded_read(
                    &Path::new(crate::defs::WORKING_DIR).join("lunexsu-sui/last_error"),
                    4096,
                )
                .ok()
                .map_or_else(
                    || "Broker Binder health has not been established".to_owned(),
                    |bytes| String::from_utf8_lossy(&bytes).into_owned(),
                )
            },
            |error| format!("{error:#}"),
        )
    } else {
        String::new()
    };
    Ok(
        serde_json::json!({"desired_enabled":desired.enabled,"pending_reboot":pending_reboot,
        "service_healthy":healthy,"bridge":bridge,"permission_host_available":false,"reason":reason,
        "state":if !desired.enabled {"disabled"} else if pending_reboot {"pending_reboot"}
            else if healthy {"enabled"} else {"unavailable"}}),
    )
}
