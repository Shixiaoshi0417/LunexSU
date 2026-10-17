use adb_client::ADBDeviceExt;
use adb_client::tcp::ADBTcpDevice;
use anyhow::{Context, Result, bail, ensure};
use log::{error, info, warn};
use prop_rs_android::resetprop::ResetProp;
use prop_rs_android::sys_prop;
use std::io::{self, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::os::unix::fs::OpenOptionsExt;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::process::Command;

#[path = "magica_state.rs"]
mod state;

const fn resetprop() -> ResetProp {
    ResetProp {
        skip_svc: true,
        persistent: false,
        persist_only: false,
        verbose: false,
        show_context: false,
        rebuild: false,
    }
}

fn io_error(error: impl std::fmt::Display) -> io::Error {
    io::Error::other(error.to_string())
}

struct AndroidAdbState;

impl state::AdbState for AndroidAdbState {
    fn property(&mut self, name: &str) -> io::Result<Option<String>> {
        Ok(resetprop().get(name))
    }

    fn permission_files(&mut self) -> io::Result<Vec<String>> {
        // This resetprop version dual-writes appcompat override areas, but does
        // not expose their original values for an exact per-property snapshot.
        // Refuse before mutation rather than silently overwrite those values
        // or restore whole shared property files over concurrent system changes.
        if std::path::Path::new("/dev/__properties__/appcompat_override").try_exists()? {
            return Err(io::Error::other(
                "Magica cannot safely snapshot appcompat override properties on this device",
            ));
        }
        let mut paths = vec!["/dev/__properties__/properties_serial".to_owned()];
        for name in state::PROPERTIES {
            let context = sys_prop::get_context(name).map_err(io_error)?;
            paths.push(format!("/dev/__properties__/{context}"));
        }
        Ok(paths)
    }

    fn mode(&mut self, path: &str) -> io::Result<u32> {
        Ok(std::fs::metadata(path)?.permissions().mode())
    }

    fn chmod(&mut self, path: &str, mode: u32) -> io::Result<()> {
        // Keep the existing limited-root Magica chmod command contract.
        checked_command("chmod", &[&format!("{:04o}", mode & 0o7777), path]).map_err(io_error)
    }

    fn set_direct(&mut self, name: &str, value: Option<&str>) -> io::Result<()> {
        let rp = resetprop();
        if let Some(value) = value {
            if rp.set(name, value).map_err(io_error)? {
                return Err(io::Error::other(format!(
                    "property {name} requires a property-area rebuild"
                )));
            }
        } else if rp.get(name).is_some() {
            ensure_deleted(&rp, name)?;
        }
        if rp.get(name).as_deref() != value {
            return Err(io::Error::other(format!(
                "property {name} did not restore/set correctly"
            )));
        }
        Ok(())
    }

    fn set_service(&mut self, name: &str, value: &str) -> io::Result<()> {
        checked_command("setprop", &[name, value]).map_err(io_error)
    }

    fn restart(&mut self) -> io::Result<()> {
        checked_command("setprop", &["ctl.restart", "adbd"]).map_err(io_error)
    }
}

fn ensure_deleted(rp: &ResetProp, name: &str) -> io::Result<()> {
    if !rp.delete(name).map_err(io_error)? {
        return Err(io::Error::other(format!(
            "failed to delete temporary property {name}"
        )));
    }
    Ok(())
}

fn checked_command(command: &str, args: &[&str]) -> Result<()> {
    info!("Magica: {command} {}", args.join(" "));
    let status = Command::new(command)
        .args(args)
        .status()
        .with_context(|| format!("Failed to execute {command}"))?;
    ensure!(
        status.success(),
        "{command} {} exited with {status}",
        args.join(" ")
    );
    Ok(())
}

fn connect_to_device(port: u16) -> Result<ADBTcpDevice> {
    const MAX_RETRIES: u32 = 30;
    for attempt in 1..=MAX_RETRIES {
        info!("Waiting for adbd to restart... (attempt {attempt}/{MAX_RETRIES})");
        std::thread::sleep(std::time::Duration::from_secs(1));

        let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port);
        info!("Connecting to ADB device at {addr}");
        match ADBTcpDevice::new(addr).context("Failed to create ADBTcpDevice") {
            Ok(device) => return Ok(device),
            Err(e) => error!("Failed to connect to ADB device: {e:?}, retry after 1s"),
        }
    }
    bail!("Failed to connect to ADB device after {MAX_RETRIES} attempts")
}

fn dispatch(port: u16, package_name: &str, allow_shell: bool, result_file: &Path) -> Result<()> {
    let mut device = connect_to_device(port)?;
    let self_path = std::env::current_exe().context("Failed to get self exe path")?;
    let self_path = self_path
        .to_str()
        .context("Magica executable path is not UTF-8")?;
    let allow_shell_arg = if allow_shell { " --allow-shell" } else { "" };
    // ADB's legacy shell transport does not return an exit status itself.
    // This reports shell dispatch acceptance, not child success. Redirect the
    // background child's standard FDs so daemonization cannot keep the ADB
    // stream open; require its private completion report below with a deadline.
    let cmd = format!(
        "{} late-load --post-magica --magica-result {} --package-name {}{} >/dev/null 2>&1 & status=$?; printf '\\n{}%s\\n' \"$status\"",
        state::shell_quote(self_path),
        state::shell_quote(
            result_file
                .to_str()
                .context("Magica result path is not UTF-8")?
        ),
        state::shell_quote(package_name),
        allow_shell_arg,
        state::STATUS_MARKER,
    );
    info!("Dispatching late-load via adb shell...");
    let mut stdout = Vec::new();
    let mut stderr = Vec::new();
    let result = device.shell_command(&cmd, Some(&mut stdout), Some(&mut stderr));
    if !stdout.is_empty() {
        info!("stdout: {}", String::from_utf8_lossy(&stdout));
    }
    if !stderr.is_empty() {
        info!("stderr: {}", String::from_utf8_lossy(&stderr));
    }
    result.context("Magica adb shell dispatch failed")?;
    state::check_dispatch_status(&stdout)?;
    state::wait_for_child(
        crate::defs::BOOT_STAGE_TIMEOUT.as_secs() + 30,
        || match std::fs::read(result_file) {
            Ok(bytes) => Ok(Some(bytes)),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(None),
            Err(error) => Err(error),
        },
        || std::thread::sleep(std::time::Duration::from_secs(1)),
    )?;
    Ok(())
}

pub fn report_child_result(path: &Path, result: &Result<()>) -> Result<()> {
    let pending = path.with_extension("pending");
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&pending)
        .context("create private Magica child completion report")?;
    match result {
        Ok(()) => file.write_all(b"OK\n"),
        Err(error) => file.write_all(format!("ERROR\n{error:#}").as_bytes()),
    }?;
    file.sync_all()?;
    drop(file);
    std::fs::rename(pending, path).context("publish Magica child completion report")
}

pub fn run(port: u16, package_name: &str, allow_shell: bool, allow_network: bool) -> Result<()> {
    ensure!(
        allow_network,
        "Magica cannot guarantee a loopback-only listener on vendor adbd. The temporary \
         unauthenticated root listener may bind ALL interfaces; isolate the device's network \
         and explicitly accept this risk with --magica-allow-network"
    );
    ensure!(
        rustix::process::getuid().as_raw() == 0,
        "must be run as root"
    );
    ensure!(port != 0, "Magica TCP port must not be zero");
    // Separate, root-private directory; creation failure happens before any ADB
    // changes. Atomic child reports survive late-load's daemonize/reset_std.
    let completion = tempfile::Builder::new()
        .prefix("ksu-magica-")
        .tempdir_in("/data/local/tmp")
        .context("create private Magica completion directory")?;
    std::fs::set_permissions(completion.path(), std::fs::Permissions::from_mode(0o700))?;
    let result_file = completion.path().join("result");
    sys_prop::init().context("Failed to initialize system property API")?;
    warn!(
        "Temporary unauthenticated root ADB on port {port} may listen on ALL interfaces; \
         connecting to 127.0.0.1 does not constrain the listener"
    );
    // The initiating process owns the snapshot and restoration. No child flag,
    // successful connect, or successful late-load is needed for cleanup.
    state::with_temporary_adb(AndroidAdbState, port, || {
        dispatch(port, package_name, allow_shell, &result_file)
            .map_err(|error| io::Error::other(format!("{error:#}")))
    })?;
    Ok(())
}
