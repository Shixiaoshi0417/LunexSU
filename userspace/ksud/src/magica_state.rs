//! The Magica transaction, kept independent of Android for host fault injection.
use std::io;

pub const PROPERTIES: [&str; 4] = [
    "ro.debuggable",
    "ro.adb.secure",
    "service.adb.root",
    "service.adb.tcp.port",
];

pub trait AdbState {
    fn property(&mut self, name: &str) -> io::Result<Option<String>>;
    fn permission_files(&mut self) -> io::Result<Vec<String>>;
    fn mode(&mut self, path: &str) -> io::Result<u32>;
    fn chmod(&mut self, path: &str, mode: u32) -> io::Result<()>;
    fn set_direct(&mut self, name: &str, value: Option<&str>) -> io::Result<()>;
    fn set_service(&mut self, name: &str, value: &str) -> io::Result<()>;
    fn restart(&mut self) -> io::Result<()>;
}

struct TemporaryAdb<B: AdbState> {
    backend: B,
    properties: Vec<(&'static str, Option<String>)>,
    permissions: Vec<(String, u32)>,
    dirty: bool,
}

impl<B: AdbState> TemporaryAdb<B> {
    fn snapshot(mut backend: B) -> io::Result<Self> {
        let mut properties = Vec::new();
        for name in PROPERTIES {
            properties.push((name, backend.property(name)?));
        }
        let mut paths = backend.permission_files()?;
        paths.sort();
        paths.dedup();
        let mut permissions = Vec::new();
        for path in paths {
            let mode = backend.mode(&path)?;
            permissions.push((path, mode));
        }
        Ok(Self {
            backend,
            properties,
            permissions,
            dirty: false,
        })
    }

    fn enable(&mut self, port: u16) -> io::Result<()> {
        // Arm before the first mutation: even a failed chmod/setprop may have
        // changed state before returning an error.
        self.dirty = true;
        for (path, _) in &self.permissions {
            self.backend.chmod(path, 0o644)?;
        }
        self.backend.set_direct(PROPERTIES[0], Some("1"))?;
        self.backend.set_direct(PROPERTIES[1], Some("0"))?;
        for (path, mode) in &self.permissions {
            self.backend.chmod(path, *mode)?;
        }
        self.backend.set_service(PROPERTIES[2], "1")?;
        self.backend.set_service(PROPERTIES[3], &port.to_string())?;
        self.backend.restart()
    }

    fn restore(&mut self) -> io::Result<()> {
        if !self.dirty {
            return Ok(());
        }
        let mut errors = Vec::new();
        for (path, _) in &self.permissions {
            if let Err(error) = self.backend.chmod(path, 0o644) {
                errors.push(format!("make {path} writable: {error}"));
            }
        }
        for (name, value) in &self.properties {
            if let Err(error) = self.backend.set_direct(name, value.as_deref()) {
                errors.push(format!("restore {name}: {error}"));
            }
        }
        // Always try every permission and the restart, including when a
        // property restore fails. Never hide cleanup errors behind success.
        for (path, mode) in &self.permissions {
            if let Err(error) = self.backend.chmod(path, *mode) {
                errors.push(format!("restore permissions for {path}: {error}"));
            }
        }
        if let Err(error) = self.backend.restart() {
            errors.push(format!("restart restored adbd: {error}"));
        }
        if errors.is_empty() {
            self.dirty = false;
            Ok(())
        } else {
            Err(io::Error::other(errors.join("; ")))
        }
    }
}

impl<B: AdbState> Drop for TemporaryAdb<B> {
    fn drop(&mut self) {
        // Fallback for unwinding; normal exits explicitly restore and propagate
        // errors. Like any in-process guard, this cannot handle SIGKILL/abort.
        if let Err(error) = self.restore() {
            eprintln!("Magica emergency restoration failed: {error}");
        }
    }
}

pub fn with_temporary_adb<B: AdbState, T>(
    backend: B,
    port: u16,
    work: impl FnOnce() -> io::Result<T>,
) -> io::Result<T> {
    if port == 0 {
        return Err(io::Error::other("Magica TCP port must not be zero"));
    }
    let mut guard = TemporaryAdb::snapshot(backend)?;
    let result = guard.enable(port).and_then(|()| work());
    let restored = guard.restore();
    match (result, restored) {
        (Ok(value), Ok(())) => Ok(value),
        (Err(error), Ok(())) => Err(error),
        (Ok(_), Err(error)) => Err(io::Error::other(format!(
            "Magica restoration failed: {error}"
        ))),
        (Err(error), Err(restore)) => Err(io::Error::other(format!(
            "{error}; Magica restoration also failed: {restore}"
        ))),
    }
}

pub fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}

pub const STATUS_MARKER: &str = "LUNEXSU_MAGICA_DISPATCH_STATUS=";

pub fn check_dispatch_status(stdout: &[u8]) -> io::Result<()> {
    let output = String::from_utf8_lossy(stdout);
    let status = output
        .lines()
        .rev()
        .find_map(|line| line.strip_prefix(STATUS_MARKER));
    match status {
        Some("0") => Ok(()),
        Some(code) => Err(io::Error::other(format!(
            "Magica child dispatch exited with {code}"
        ))),
        None => Err(io::Error::other(
            "Magica child dispatch did not report an exit status",
        )),
    }
}

pub fn wait_for_child(
    attempts: u64,
    mut read_result: impl FnMut() -> io::Result<Option<Vec<u8>>>,
    mut wait: impl FnMut(),
) -> io::Result<()> {
    for attempt in 0..attempts {
        if let Some(bytes) = read_result()? {
            if bytes == b"OK\n" {
                return Ok(());
            }
            let result = std::str::from_utf8(&bytes).map_err(io::Error::other)?;
            if let Some(error) = result.strip_prefix("ERROR\n") {
                return Err(io::Error::other(format!(
                    "Magica late-load child failed: {error}"
                )));
            }
            return Err(io::Error::other("invalid Magica child completion report"));
        }
        if attempt + 1 < attempts {
            wait();
        }
    }
    Err(io::Error::other(
        "Magica child did not report completion before timeout",
    ))
}
