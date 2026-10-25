use crate::state::{State, valid_boot_id};
const FIRST: &str = "00112233-4455-6677-8899-aabbccddeeff";
const NEXT: &str = "11112233-4455-6677-8899-aabbccddeeff";

#[test]
fn activation_needs_a_real_reboot_and_repeated_enable_is_idempotent() {
    let disabled = State::default();
    assert!(!disabled.after_reboot(FIRST));
    let enabled = disabled.request(true, FIRST).unwrap();
    assert!(!enabled.after_reboot(FIRST));
    assert!(enabled.after_reboot(NEXT));
    assert_eq!(enabled.request(true, NEXT).unwrap(), enabled);
    let stopped = enabled.request(false, NEXT).unwrap();
    assert!(!stopped.after_reboot(FIRST));
    assert!(!stopped.after_reboot(NEXT));
    let restarted = stopped.request(true, NEXT).unwrap();
    assert!(!restarted.after_reboot(NEXT));
}

#[test]
fn malformed_or_missing_boot_and_state_never_enable_the_broker() {
    for boot in [
        "",
        "../boot",
        "00112233-4455-6677-8899-AABBCCDDEEFF",
        "00112233445566778899aabbccddeeff",
    ] {
        assert!(!valid_boot_id(boot));
        assert!(!State::default().after_reboot(boot));
        assert!(State::default().request(true, boot).is_err());
    }
    for json in [
        r#"{"schema_version":2,"enabled":false,"requested_boot":null}"#,
        r#"{"schema_version":1,"enabled":true,"requested_boot":null}"#,
        r#"{"schema_version":1,"enabled":true,"requested_boot":"invalid"}"#,
        r#"{"schema_version":1,"enabled":false,"requested_boot":null,"extra":1}"#,
        r#"{"schema_version":1,"enabled":false,"enabled":true,"requested_boot":null}"#,
    ] {
        assert!(State::parse(json.as_bytes()).is_err(), "{json}");
    }
    assert!(State::parse(&vec![b' '; 4097]).is_err());
    assert_eq!(
        State::parse(&serde_json::to_vec(&State::default()).unwrap()).unwrap(),
        State::default()
    );
}

#[test]
fn managed_policy_has_no_adb_root_permissions_and_uses_the_kernel_domain() {
    let policy = std::str::from_utf8(crate::state::POLICY).unwrap();
    let rules: Vec<_> = policy
        .lines()
        .filter(|line| !line.starts_with('#') && !line.is_empty())
        .collect();
    assert_eq!(
        rules,
        [
            "allow ksu ksu process setcurrent",
            "allow ksu shell process dyntransition"
        ]
    );
    assert!(
        include_str!("../../../../../kernel/selinux/selinux.h")
            .contains("#define KERNEL_SU_DOMAIN \"ksu\"")
    );
}
