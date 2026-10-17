//! Boot lifecycle control flow with no Android or partition access.
use std::io::{self, Read};

pub struct BootctlOutput {
    pub success: bool,
    pub stdout: Vec<u8>,
}

pub trait Bootctl {
    fn run(&mut self, args: &[&str]) -> io::Result<BootctlOutput>;
}

fn checked(bootctl: &mut impl Bootctl, args: &[&str]) -> io::Result<Vec<u8>> {
    let output = bootctl.run(args)?;
    if !output.success {
        return Err(io::Error::other(format!(
            "bootctl {} failed",
            args.join(" ")
        )));
    }
    Ok(output.stdout)
}

pub fn other_slot_suffix(current: &str) -> io::Result<&'static str> {
    match current {
        "_a" => Ok("_b"),
        "_b" => Ok("_a"),
        _ => Err(io::Error::other(format!(
            "invalid A/B slot suffix: {current:?}"
        ))),
    }
}

pub struct OtaPlan {
    target: &'static str,
}

pub fn prepare_ota(bootctl: &mut impl Bootctl, suffix: &str) -> io::Result<OtaPlan> {
    other_slot_suffix(suffix)?;
    checked(bootctl, &["hal-info"])?;
    let output = checked(bootctl, &["get-current-slot"])?;
    let current = std::str::from_utf8(&output)
        .map_err(io::Error::other)?
        .trim();
    let target = match (current, suffix) {
        ("0", "_a") => "1",
        ("1", "_b") => "0",
        _ => {
            return Err(io::Error::other(format!(
                "invalid or inconsistent bootctl current slot {current:?} and suffix {suffix:?}"
            )));
        }
    };
    Ok(OtaPlan { target })
}

pub fn activate_ota(bootctl: &mut impl Bootctl, plan: &OtaPlan) -> io::Result<()> {
    checked(bootctl, &["set-active-boot-slot", plan.target])?;
    Ok(())
}

pub fn restore_before_cleanup<T, E>(
    restore: impl FnOnce() -> Result<(), E>,
    cleanup: impl FnOnce() -> Result<T, E>,
) -> Result<T, E> {
    restore()?;
    cleanup()
}

pub fn verify_restored_image(mut reader: impl Read, expected: &[u8]) -> io::Result<()> {
    let mut buffer = [0_u8; 8192];
    for chunk in expected.chunks(buffer.len()) {
        let actual = &mut buffer[..chunk.len()];
        reader.read_exact(actual)?;
        if actual != chunk {
            return Err(io::Error::other("restored boot image read-back mismatch"));
        }
    }
    Ok(())
}
