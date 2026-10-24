use crate::identity::{self, Binding};

#[test]
fn records_require_the_core_binding_not_just_a_package_or_uid() {
    let hash = "ab".repeat(32);
    let binding = Binding {
        package: "com.shixiaoshi0417.lunexsu",
        certificate_size: 803,
        certificate_sha256: &hash,
    };
    assert!(binding.record(10001, 803, &hash, None).is_ok());
    for uid in [0, 1000, 2000, 9999, 20000, 110001] {
        assert!(binding.record(uid, 803, &hash, None).is_err());
    }
    assert!(binding.record(10001, 802, &hash, None).is_err());
    assert!(binding.record(10001, 803, &"00".repeat(32), None).is_err());
    let old = binding.record(10002, 803, &hash, None).unwrap();
    assert_eq!(
        binding
            .record(10001, 803, &hash, Some(&old))
            .unwrap()
            .previous_uid,
        10002
    );
    assert_eq!(
        binding
            .record(10002, 803, &hash, Some(&old))
            .unwrap()
            .previous_uid,
        -1
    );
    let changed = binding.record(10001, 803, &hash, Some(&old)).unwrap();
    assert_eq!(
        binding
            .record(10001, 803, &hash, Some(&changed))
            .unwrap()
            .previous_uid,
        10002
    );
    for package in ["com.shixiaoshi0417.lunexsu.demo", "me.yuki.fina", "evil"] {
        assert!(!Binding { package, ..binding }.accepts(10001, 803, &hash));
    }
    assert!(
        !Binding {
            certificate_size: 1,
            ..binding
        }
        .accepts(10001, 1, &hash)
    );
    assert!(
        !Binding {
            certificate_sha256: &"zz".repeat(32),
            ..binding
        }
        .accepts(10001, 803, &"zz".repeat(32))
    );
}

#[test]
fn package_queries_cannot_select_a_prefix_match_or_ambiguous_uid() {
    let package = "com.shixiaoshi0417.lunexsu";
    assert_eq!(
        identity::package_uid(&format!("package:{package} uid:10042\n"), package).unwrap(),
        10042
    );
    for value in [
        format!("package:{package}.demo uid:10042"),
        format!("package:{package} uid:10042\npackage:{package} uid:10043"),
        format!("package:{package} uid:110042"),
        format!("package:{package} uid:10042 trailing"),
        "x".repeat(65537),
    ] {
        assert!(identity::package_uid(&value, package).is_err());
    }
}

#[test]
fn split_apk_queries_select_only_an_unambiguous_installed_base() {
    assert_eq!(
        identity::base_apk(
            "package:/data/app/pkg/base.apk\npackage:/data/app/pkg/split_config.apk\n"
        )
        .unwrap(),
        "/data/app/pkg/base.apk"
    );
    for value in [
        "",
        "package:/tmp/base.apk",
        "package:/data/app/../base.apk",
        "package:/data/app/a/base.apk\npackage:/data/app/b/base.apk",
        "package:/data/app/a/split_a.apk\npackage:/data/app/a/split_b.apk",
    ] {
        assert!(identity::base_apk(value).is_err());
    }
}
