//! Bounded host-tool queries. This never runs the embedded Sui executables.
use anyhow::{Result, ensure};
use std::{
    io::Read,
    path::Path,
    process::{Child, Command, Output, Stdio},
    thread,
    time::{Duration, Instant},
};

const OUTPUT_LIMIT: u64 = 64 * 1024;

struct ChildGuard {
    child: Child,
    reaped: bool,
}

impl Drop for ChildGuard {
    fn drop(&mut self) {
        if !self.reaped {
            let _ = self.child.kill();
            let _ = self.child.wait();
        }
    }
}

fn contents(file: &tempfile::NamedTempFile) -> Result<Vec<u8>> {
    let mut bytes = Vec::new();
    file.reopen()?
        .take(OUTPUT_LIMIT + 1)
        .read_to_end(&mut bytes)?;
    ensure!(
        bytes.len() as u64 <= OUTPUT_LIMIT,
        "Sui helper exceeded output limit"
    );
    Ok(bytes)
}

pub fn wait_output(command: &mut Command, directory: &Path, timeout: Duration) -> Result<Output> {
    // Regular private files avoid full-pipe deadlocks. Check their growth while the
    // helper is alive as well as bounding the final read; timeout alone isn't a disk limit.
    let stdout = tempfile::NamedTempFile::new_in(directory)?;
    let stderr = tempfile::NamedTempFile::new_in(directory)?;
    let child = command
        .stdin(Stdio::null())
        .stdout(stdout.as_file().try_clone()?)
        .stderr(stderr.as_file().try_clone()?)
        .spawn()?;
    let mut guard = ChildGuard {
        child,
        reaped: false,
    };
    let deadline = Instant::now() + timeout;
    loop {
        ensure!(
            stdout.as_file().metadata()?.len() <= OUTPUT_LIMIT
                && stderr.as_file().metadata()?.len() <= OUTPUT_LIMIT,
            "Sui helper exceeded output limit"
        );
        if let Some(status) = guard.child.try_wait()? {
            guard.reaped = true;
            return Ok(Output {
                status,
                stdout: contents(&stdout)?,
                stderr: contents(&stderr)?,
            });
        }
        ensure!(Instant::now() < deadline, "Sui helper timed out");
        thread::sleep(Duration::from_millis(20));
    }
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    #[test]
    fn captures_both_streams_and_exit_status_without_leftover_files() {
        let directory = tempfile::tempdir().unwrap();
        let result = wait_output(
            Command::new("/bin/sh").args(["-c", "printf ok; printf problem >&2; exit 7"]),
            directory.path(),
            Duration::from_secs(5),
        )
        .unwrap();
        assert_eq!(result.stdout, b"ok");
        assert_eq!(result.stderr, b"problem");
        assert_eq!(result.status.code(), Some(7));
        assert_eq!(directory.path().read_dir().unwrap().count(), 0);
    }

    #[test]
    fn stops_excess_output_on_either_stream_before_waiting_for_timeout() {
        for redirect in ["", " >&2"] {
            let directory = tempfile::tempdir().unwrap();
            let script = format!(
                "while :; do printf '0123456789012345678901234567890123456789'{redirect}; done"
            );
            let error = wait_output(
                Command::new("/bin/sh").args(["-c", &script]),
                directory.path(),
                Duration::from_secs(5),
            )
            .unwrap_err();
            assert!(error.to_string().contains("output limit"), "{error}");
            assert_eq!(directory.path().read_dir().unwrap().count(), 0);
        }
    }

    #[test]
    fn timeout_and_spawn_failure_reap_and_clean_private_files() {
        let directory = tempfile::tempdir().unwrap();
        let error = wait_output(
            Command::new("/bin/sh").args(["-c", "exec sleep 30"]),
            directory.path(),
            Duration::from_millis(30),
        )
        .unwrap_err();
        assert!(error.to_string().contains("timed out"));
        assert_eq!(directory.path().read_dir().unwrap().count(), 0);
        assert!(
            wait_output(
                &mut Command::new(directory.path().join("absent-helper")),
                directory.path(),
                Duration::from_secs(1)
            )
            .is_err()
        );
        assert_eq!(directory.path().read_dir().unwrap().count(), 0);
    }
}
