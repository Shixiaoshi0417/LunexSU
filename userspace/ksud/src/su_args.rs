use std::cmp::Ordering;

pub struct ShellArgs {
    pub args: Vec<String>,
    pub executable: Option<String>,
    pub exec_args: Option<Vec<String>>,
}

pub fn split(env_args: Vec<String>) -> ShellArgs {
    let mut executable = None;
    let mut exec_args = None;
    let first_option_c = env_args
        .iter()
        .position(|arg| arg == "-c")
        .unwrap_or(usize::MAX);
    let first_non_option = env_args
        .windows(3)
        .position(|arg| {
            !arg[1].starts_with('-')
                && !arg[2].starts_with('-')
                && !(arg[0].starts_with("-g")
                    || arg[0].starts_with("-G")
                    || arg[0].starts_with("-s")
                    || arg[0].starts_with("-Z")
                    || arg[0] == "--group"
                    || arg[0] == "--supp-group"
                    || arg[0] == "--shell"
                    || arg[0] == "--context")
        })
        .map_or(usize::MAX, |idx| idx + 1);
    let args = match first_non_option.cmp(&first_option_c) {
        Ordering::Equal => env_args,
        Ordering::Less => {
            executable = Some(env_args[first_non_option + 1].clone());
            exec_args = Some(env_args[first_non_option + 2..].to_vec());
            env_args[..=first_non_option].to_vec()
        }
        Ordering::Greater => {
            let rest = env_args[first_option_c + 1..].to_vec();
            let mut args = env_args[..first_option_c].to_vec();
            args.push("-c".to_string());
            if !rest.is_empty() {
                args.push(rest.join(" "));
            }
            args
        }
    };
    ShellArgs {
        args,
        executable,
        exec_args,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(input: &str) -> ShellArgs {
        split(input.split_whitespace().map(str::to_owned).collect())
    }

    #[test]
    fn separated_option_values_are_not_users_or_executables() {
        for option in [
            "--shell /system/bin/sh",
            "--supp-group 3003",
            "--context u:r:shell:s0",
            "--group 2000",
            "-s /system/bin/sh",
            "-G 3003",
            "--shell=/system/bin/sh",
            "--supp-group=3003",
            "--context=u:r:shell:s0",
        ] {
            let command = args(&format!("su {option} 2000 -c id -u"));
            assert!(command.executable.is_none(), "{option}");
            assert_eq!(command.args.last().unwrap(), "id -u");
            let direct = args(&format!("su {option} 2000 /system/bin/id -u"));
            assert_eq!(
                direct.executable.as_deref(),
                Some("/system/bin/id"),
                "{option}"
            );
            assert_eq!(direct.exec_args.unwrap(), ["-u"]);
            assert_eq!(direct.args.last().unwrap(), "2000");
        }
    }
}
