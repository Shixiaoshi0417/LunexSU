fn main() {
    let bindings = bindgen::Builder::default()
        .header("../../src/ksu_uapi.h")
        .clang_args(["-x", "c++", "-I../../../../"])
        .allowlist_var("KSU_SEPOLICY_.*")
        .parse_callbacks(Box::new(bindgen::CargoCallbacks::new()))
        .generate()
        .expect("generate policy constants from production UAPI");
    bindings
        .write_to_file(
            std::path::PathBuf::from(std::env::var_os("OUT_DIR").unwrap()).join("bindings.rs"),
        )
        .unwrap();
}
