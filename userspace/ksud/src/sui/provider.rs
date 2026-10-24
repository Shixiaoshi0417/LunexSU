//! Read-only prerequisite checks, independent of built-in module ownership.
use std::{
    ffi::CString,
    fs::{File, OpenOptions},
    io,
    os::{
        fd::{AsRawFd, FromRawFd},
        unix::fs::OpenOptionsExt,
    },
    path::Path,
};

use serde::Serialize;

const IDS: [&str; 2] = ["rezygisk", "zygisksu"];

#[derive(Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum State {
    Ready,
    Missing,
    PendingReboot,
    Conflict,
    Unavailable,
}

#[derive(Debug, Serialize)]
pub struct Assessment {
    pub state: State,
    pub providers: Vec<&'static str>,
}

impl Assessment {
    pub const fn unavailable() -> Self {
        Self {
            state: State::Unavailable,
            providers: Vec::new(),
        }
    }

    pub fn require_ready(&self) -> anyhow::Result<()> {
        anyhow::ensure!(
            self.state == State::Ready,
            "Enable exactly one Zygisk provider (built-in or external ReZygisk/NeoZygisk/Zygisk Next) and fully reboot first; prerequisite: {:?}",
            self.state
        );
        Ok(())
    }
}

fn directory(path: &Path) -> io::Result<Option<File>> {
    match OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC)
        .open(path)
    {
        Ok(file) => Ok(Some(file)),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error),
    }
}

fn child(parent: &File, id: &str) -> io::Result<Option<File>> {
    let id = CString::new(id)?;
    // Pin each real module directory; never traverse a symlink or open a FIFO.
    let fd = unsafe {
        libc::openat(
            parent.as_raw_fd(),
            id.as_ptr(),
            libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
        )
    };
    if fd >= 0 {
        Ok(Some(unsafe { File::from_raw_fd(fd) }))
    } else {
        let error = io::Error::last_os_error();
        if error.kind() == io::ErrorKind::NotFound {
            Ok(None)
        } else {
            Err(error)
        }
    }
}

fn entry(dir: &File, name: &str) -> io::Result<Option<libc::stat>> {
    let name = CString::new(name)?;
    let mut stat = std::mem::MaybeUninit::uninit();
    let result = unsafe {
        libc::fstatat(
            dir.as_raw_fd(),
            name.as_ptr(),
            stat.as_mut_ptr(),
            libc::AT_SYMLINK_NOFOLLOW,
        )
    };
    if result == 0 {
        Ok(Some(unsafe { stat.assume_init() }))
    } else {
        let error = io::Error::last_os_error();
        if error.kind() == io::ErrorKind::NotFound {
            Ok(None)
        } else {
            Err(error)
        }
    }
}

fn enabled(dir: &File) -> io::Result<bool> {
    let prop = entry(dir, "module.prop")?;
    if prop.is_none_or(|stat| stat.st_mode & libc::S_IFMT != libc::S_IFREG) {
        return Err(io::Error::other("unsafe or missing Zygisk module metadata"));
    }
    Ok(entry(dir, "disable")?.is_none() && entry(dir, "remove")?.is_none())
}

/// Staged updates and control marks are inspected, never followed, created or removed.
/// The known `zygisksu` ID covers both NeoZygisk and external Zygisk Next.
pub fn inspect(live: &Path, updates: &Path) -> io::Result<Assessment> {
    let live = directory(live)?;
    let updates = directory(updates)?;
    let mut providers = Vec::new();
    let mut candidates = 0;
    let mut active = 0;
    let mut pending = false;
    for id in IDS {
        let current = live
            .as_ref()
            .map(|root| child(root, id))
            .transpose()?
            .flatten();
        let staged = updates
            .as_ref()
            .map(|root| child(root, id))
            .transpose()?
            .flatten();
        if current.is_some() || staged.is_some() {
            providers.push(id);
        }
        let mut candidate = false;
        if let Some(dir) = current {
            candidate = enabled(&dir)?;
            if candidate {
                active += 1;
            }
            pending |= entry(&dir, "update")?.is_some() || entry(&dir, "remove")?.is_some();
        }
        if let Some(dir) = staged {
            candidate |= enabled(&dir)?;
            pending = true;
        }
        if candidate {
            candidates += 1;
        }
    }
    let state = if candidates > 1 {
        State::Conflict
    } else if pending {
        State::PendingReboot
    } else if active == 1 {
        State::Ready
    } else {
        State::Missing
    };
    Ok(Assessment { state, providers })
}
