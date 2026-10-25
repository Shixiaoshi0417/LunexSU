use anyhow::{Context, Result};

/// Once services have stopped, attempt restart even if the intermediate stage fails.
pub fn run(stage: impl FnOnce() -> Result<()>, restart: impl FnOnce() -> Result<()>) -> Result<()> {
    let stage_result = stage();
    let restart_result = restart();
    match (stage_result, restart_result) {
        (Err(stage), Err(restart)) => {
            Err(stage).with_context(|| format!("restart also failed: {restart:#}"))
        }
        (Err(stage), Ok(())) => Err(stage),
        (Ok(()), result) => result,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    #[test]
    fn restart_runs_after_failed_stage_and_both_errors_are_preserved() {
        for stage_fails in [false, true] {
            for restart_fails in [false, true] {
                let order = RefCell::new(Vec::new());
                let result = run(
                    || {
                        order.borrow_mut().push("stage");
                        anyhow::ensure!(!stage_fails, "stage failed");
                        Ok(())
                    },
                    || {
                        order.borrow_mut().push("restart");
                        anyhow::ensure!(!restart_fails, "restart failed");
                        Ok(())
                    },
                );
                assert_eq!(*order.borrow(), ["stage", "restart"]);
                assert_eq!(result.is_err(), stage_fails || restart_fails);
                if let Err(error) = result {
                    let error = format!("{error:#}");
                    assert!(!stage_fails || error.contains("stage failed"));
                    assert!(!restart_fails || error.contains("restart failed"));
                }
            }
        }
    }
}
