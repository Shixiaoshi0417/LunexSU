//! Monitor one broker generation, including both of its server Binders.
use std::{
    io,
    process::Child,
    thread,
    time::{Duration, Instant},
};

#[derive(Debug, PartialEq, Eq)]
pub enum Outcome {
    Stopped,
    Exited,
    Unhealthy,
}

pub fn servers_alive(diagnostics: &serde_json::Value) -> bool {
    ["root_binder_alive", "shell_binder_alive"]
        .iter()
        .all(|key| diagnostics["bridge"][key].as_bool() == Some(true))
}

struct ChildGuard<'a>(&'a mut Child);

impl Drop for ChildGuard<'_> {
    fn drop(&mut self) {
        // Also reap on monitoring errors. Killing root triggers shell's parent-death guard.
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

struct Timing {
    startup: Duration,
    probe: Duration,
    tick: Duration,
}

pub fn watch(
    child: &mut Child,
    keep_running: impl FnMut() -> bool,
    probe: impl FnMut() -> bool,
) -> io::Result<Outcome> {
    watch_with_timing(
        child,
        keep_running,
        probe,
        &Timing {
            startup: Duration::from_secs(60),
            probe: Duration::from_secs(5),
            tick: Duration::from_secs(1),
        },
    )
}

fn watch_with_timing(
    child: &mut Child,
    mut keep_running: impl FnMut() -> bool,
    mut probe: impl FnMut() -> bool,
    timing: &Timing,
) -> io::Result<Outcome> {
    let child = ChildGuard(child);
    let started = Instant::now();
    let mut next_probe = started;
    let mut ready = false;
    loop {
        if child.0.try_wait()?.is_some() {
            return Ok(Outcome::Exited);
        }
        // Disabled/changed ownership or identity stops the supervisor rather than restarting.
        if !keep_running() {
            return Ok(Outcome::Stopped);
        }
        if Instant::now() >= next_probe {
            if probe() {
                ready = true;
            } else if ready || started.elapsed() >= timing.startup {
                return Ok(Outcome::Unhealthy);
            }
            next_probe = Instant::now() + timing.probe;
        }
        thread::sleep(timing.tick);
    }
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::process::{Command, Stdio};

    fn child() -> Child {
        Command::new("/bin/sh")
            .args(["-c", "exec sleep 30"])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .unwrap()
    }

    fn timing() -> Timing {
        Timing {
            startup: Duration::from_millis(30),
            probe: Duration::from_millis(1),
            tick: Duration::from_millis(1),
        }
    }

    #[test]
    fn losing_either_binder_reaps_the_live_root_child() {
        for missing in ["root_binder_alive", "shell_binder_alive"] {
            let mut child = child();
            let mut probes = 0;
            let result = watch_with_timing(
                &mut child,
                || true,
                || {
                    probes += 1;
                    let report = serde_json::json!({"bridge": {
                        "root_binder_alive": probes == 1 || missing != "root_binder_alive",
                        "shell_binder_alive": probes == 1 || missing != "shell_binder_alive"
                    }});
                    servers_alive(&report)
                },
                &timing(),
            )
            .unwrap();
            assert_eq!(result, Outcome::Unhealthy, "{missing}");
            assert_eq!(probes, 2);
            assert!(!child.wait().unwrap().success());
        }
    }

    #[test]
    fn startup_can_become_ready_before_the_deadline() {
        let mut child = child();
        let mut probes = 0;
        let result = watch_with_timing(
            &mut child,
            || true,
            || {
                probes += 1;
                // Initial startup, then both alive, then shell death.
                probes == 2
            },
            &Timing {
                startup: Duration::from_secs(5),
                ..timing()
            },
        )
        .unwrap();
        assert_eq!(result, Outcome::Unhealthy);
        assert_eq!(probes, 3);
        assert!(!child.wait().unwrap().success());
    }

    #[test]
    fn a_server_that_never_starts_has_a_bounded_grace_period() {
        let mut child = child();
        let start = Instant::now();
        let timing = timing();
        assert_eq!(
            watch_with_timing(&mut child, || true, || false, &timing).unwrap(),
            Outcome::Unhealthy
        );
        assert!(start.elapsed() >= timing.startup);
        assert!(!child.wait().unwrap().success());
    }

    #[test]
    fn lifecycle_stop_wins_over_health_and_reaps_root() {
        let mut child = child();
        assert_eq!(
            watch(&mut child, || false, || panic!("must not probe")).unwrap(),
            Outcome::Stopped
        );
        assert!(!child.wait().unwrap().success());
    }

    #[test]
    fn root_exit_is_observed_without_waiting_for_binder_timeout() {
        let mut child = child();
        child.kill().unwrap();
        child.wait().unwrap();
        assert_eq!(
            watch_with_timing(&mut child, || true, || panic!("must not probe"), &timing()).unwrap(),
            Outcome::Exited
        );
    }

    #[test]
    fn healthy_servers_continue_until_lifecycle_is_stopped() {
        let mut child = child();
        let mut ticks = 0;
        assert_eq!(
            watch_with_timing(
                &mut child,
                || {
                    ticks += 1;
                    ticks < 5
                },
                || true,
                &timing(),
            )
            .unwrap(),
            Outcome::Stopped
        );
        assert_eq!(ticks, 5);
        assert!(!child.wait().unwrap().success());
    }

    #[test]
    fn missing_or_invalid_diagnostics_are_not_healthy() {
        use serde_json::{Value, json};
        for value in [
            Value::Null,
            json!({}),
            json!({"bridge": null}),
            json!({"bridge": {"root_binder_alive": true}}),
            json!({"bridge": {"root_binder_alive": true, "shell_binder_alive": "true"}}),
        ] {
            assert!(!servers_alive(&value), "{value}");
        }
    }

    #[test]
    fn background_manager_is_not_a_server_liveness_failure() {
        assert!(servers_alive(&serde_json::json!({"bridge": {
            "root_binder_alive": true, "shell_binder_alive": true,
            "manager_verified": false, "permission_host_available": false
        }})));
    }
}
