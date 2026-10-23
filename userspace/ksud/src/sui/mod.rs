//! Managed Sui is default-off. A verified bundle is not evidence of a running server.
mod deployment;
mod identity;
mod payload;
mod process_helper;
mod runtime;
mod state;
mod supervision;

pub use runtime::reconcile_boot;

use anyhow::Result;
use serde::Serialize;

#[derive(clap::Subcommand, Debug)]
pub enum Command {
    /// Inspect the bundle and, when root, read the broker's actual state without starting it
    Status,
    /// Prepare the verified payload as a disabled module; does not enable or start Sui
    Prepare,
    /// Record the installed primary-user Manager only after checking the Core certificate
    RegisterManager,
    /// Enable the owned broker for the next boot; never enables a Zygisk provider
    Enable,
    /// Stop requesting the broker and disable its modules; reboot removes all injection
    Disable,
    #[command(hide = true)]
    Supervise,
}

#[derive(Serialize)]
struct Status {
    schema_version: u32,
    state: &'static str,
    payload_available: bool,
    payload_version: Option<String>,
    payload_version_code: Option<u32>,
    payload_abi: Option<String>,
    payload_members: usize,
    deployment_available: bool,
    reason: String,
}

pub fn run(command: &Command) -> Result<()> {
    match command {
        Command::Enable => runtime::set_enabled(true),
        Command::Disable => runtime::set_enabled(false),
        Command::Supervise => runtime::supervise(),
        Command::RegisterManager => {
            let manager = register_manager()?;
            println!(
                "{}",
                serde_json::json!({"schema_version":1,"manager_registered":true,"uid":manager.uid,"started":false})
            );
            Ok(())
        }
        Command::Prepare => {
            anyhow::ensure!(
                unsafe { libc::geteuid() } == 0,
                "Preparing Sui requires root"
            );
            let transactions =
                std::path::Path::new(crate::defs::WORKING_DIR).join("module_transactions");
            let paths = deployment::Paths {
                transactions: &transactions,
                updates: std::path::Path::new(crate::defs::MODULE_UPDATE_DIR),
                modules: std::path::Path::new(crate::defs::MODULE_DIR),
            };
            let abi = if std::env::consts::ARCH == "aarch64" {
                "arm64-v8a"
            } else {
                std::env::consts::ARCH
            };
            let manifest = deployment::stage_disabled(
                &paths,
                |name| Ok(crate::assets::get_asset_data(&format!("sui/{name}"))?.into_owned()),
                abi,
            )?;
            println!(
                "{}",
                serde_json::json!({"schema_version":1,"state":"disabled","pending_reboot":true,"version":manifest.version,"started":false})
            );
            Ok(())
        }
        Command::Status => {
            let abi = match std::env::consts::ARCH {
                "aarch64" => "arm64-v8a",
                other => other,
            };
            let verified = payload::validate(
                |name| Ok(crate::assets::get_asset_data(&format!("sui/{name}"))?.into_owned()),
                abi,
            );
            let mut status = Status {
                schema_version: 1,
                state: "unavailable",
                payload_available: false,
                payload_version: None,
                payload_version_code: None,
                payload_abi: None,
                payload_members: 0,
                deployment_available: false,
                reason: String::new(),
            };
            match verified {
                Ok(manifest) => {
                    status.state = "disabled";
                    status.payload_available = true;
                    status.payload_version = Some(manifest.version);
                    status.payload_version_code = Some(manifest.version_code);
                    status.payload_abi = Some(manifest.primary_abi);
                    status.payload_members = manifest.files.len();
                }
                Err(error) => status.reason = format!("{error:#}"),
            }
            let mut value = serde_json::to_value(&status)?;
            if unsafe { libc::geteuid() } == 0 && status.payload_available {
                match runtime::status() {
                    Ok(actual) => {
                        value["state"] = actual["state"].clone();
                        value["reason"] = actual["reason"].clone();
                        value["runtime"] = actual;
                        value["deployment_available"] = serde_json::json!(true);
                    }
                    Err(error) => value["reason"] = serde_json::json!(format!("{error:#}")),
                }
            }
            println!("{value}");
            Ok(())
        }
    }
}

fn register_manager() -> Result<identity::Manager> {
    use anyhow::{Context, ensure};
    use std::{
        fs,
        io::{Read, Write},
        os::unix::fs::{OpenOptionsExt, PermissionsExt},
        path::Path,
    };
    ensure!(
        unsafe { libc::geteuid() } == 0,
        "Registering the Sui Manager requires root"
    );
    let directory = Path::new(crate::defs::WORKING_DIR).join("lunexsu-sui");
    fs::create_dir_all(&directory)?;
    ensure!(
        fs::symlink_metadata(&directory)?.is_dir(),
        "Invalid Sui state directory"
    );
    fs::set_permissions(&directory, fs::Permissions::from_mode(0o700))?;
    let _lock = crate::file_lock::FileLock::exclusive(&directory.join("manager.lock"))?;
    // Do not publish a management identity for an external or absent installation.
    let live = Path::new(crate::defs::MODULE_DIR).join(deployment::MODULE_ID);
    ensure!(
        deployment::owned(&live)?,
        "Prepare the owned Sui module before registering its Manager"
    );
    let package = crate::defs::DEFAULT_PACKAGE_NAME;
    let query = |args: &[&str]| -> Result<String> {
        let output =
            runtime::wait_output(std::process::Command::new("/system/bin/cmd").args(args), 10)?;
        ensure!(output.status.success(), "Package-manager query failed");
        Ok(String::from_utf8(output.stdout)?)
    };
    let uid = identity::package_uid(
        &query(&["package", "list", "packages", "-U", "--user", "0", package])?,
        package,
    )?;
    let apk_paths = query(&["package", "path", "--user", "0", package])?;
    let (size, hash) = crate::apk_sign::get_apk_signature(identity::base_apk(&apk_paths)?)?;
    let binding = identity::Binding {
        package,
        certificate_size: env!("KSU_EXPECTED_SIZE").parse()?,
        certificate_sha256: env!("KSU_EXPECTED_HASH"),
    };
    let destination = directory.join("manager.json");
    let previous = match fs::OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(&destination)
    {
        Ok(file) => {
            ensure!(
                file.metadata()?.is_file(),
                "Invalid previous Manager record"
            );
            let mut bytes = Vec::new();
            file.take(4097).read_to_end(&mut bytes)?;
            ensure!(bytes.len() <= 4096, "Oversized Manager record");
            Some(
                serde_json::from_slice::<identity::Manager>(&bytes)
                    .context("Invalid previous Manager record")?,
            )
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => None,
        Err(error) => return Err(error.into()),
    };
    let record = binding.record(uid, size, &hash, previous.as_ref())?;
    let mut temporary = tempfile::NamedTempFile::new_in(&directory)?;
    temporary
        .as_file()
        .set_permissions(fs::Permissions::from_mode(0o600))?;
    temporary.write_all(&serde_json::to_vec(&record)?)?;
    temporary.as_file().sync_all()?;
    temporary.persist(&destination)?;
    fs::File::open(&directory)?.sync_all()?;
    fs::File::open(Path::new(crate::defs::WORKING_DIR))?.sync_all()?;
    Ok(record)
}

pub fn boot_services() {
    runtime::boot_services();
}
