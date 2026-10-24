use std::{
    fs::{File, OpenOptions},
    io,
    os::{fd::AsRawFd, unix::fs::OpenOptionsExt},
    path::Path,
};

/// Lock a stable, separate inode, never the file/directory being replaced or removed.
/// Closing the last descriptor releases the lock, including after process termination.
/// A descriptor transiently inherited by a fork closes when that child execs or exits.
pub struct FileLock {
    _file: File,
}

impl FileLock {
    pub fn exclusive(path: &Path) -> io::Result<Self> {
        Self::acquire(path, libc::LOCK_EX)
    }

    pub fn try_exclusive(path: &Path) -> io::Result<Option<Self>> {
        match Self::acquire(path, libc::LOCK_EX | libc::LOCK_NB) {
            Ok(lock) => Ok(Some(lock)),
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => Ok(None),
            Err(error) => Err(error),
        }
    }

    fn acquire(path: &Path, flags: libc::c_int) -> io::Result<Self> {
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
            .open(path)?;
        if !file.metadata()?.is_file() {
            return Err(io::Error::other("Lock is not a regular file"));
        }
        loop {
            if unsafe { libc::flock(file.as_raw_fd(), flags) } == 0 {
                return Ok(Self { _file: file });
            }
            let error = io::Error::last_os_error();
            if error.kind() != io::ErrorKind::Interrupted {
                return Err(error);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    fn available(path: &std::path::Path) -> super::FileLock {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        loop {
            if let Some(lock) = super::FileLock::try_exclusive(path).unwrap() {
                return lock;
            }
            // Parallel process tests can fork while the owner is alive. Their
            // close-on-exec copy can briefly outlive the parent's descriptor.
            assert!(
                std::time::Instant::now() < deadline,
                "Lock was not released"
            );
            std::thread::yield_now();
        }
    }

    #[test]
    fn nonblocking_supervisor_lock_does_not_queue_another_owner() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("supervisor.lock");
        let first = super::FileLock::exclusive(&path).unwrap();
        assert!(super::FileLock::try_exclusive(&path).unwrap().is_none());
        drop(first);
        let _next = available(&path);
    }

    #[test]
    fn a_duplicate_descriptor_retains_the_same_lock_until_closed() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("supervisor.lock");
        let owner = super::FileLock::exclusive(&path).unwrap();
        let duplicate = owner._file.try_clone().unwrap();
        drop(owner);
        assert!(super::FileLock::try_exclusive(&path).unwrap().is_none());
        drop(duplicate);
        let _next = available(&path);
    }
}
