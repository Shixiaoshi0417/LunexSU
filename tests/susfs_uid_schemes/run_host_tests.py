"""Pure SUSFS UID editor regressions, without Gradle, JNI, root or a SUSFS engine.

Compile the real presentation/input helpers with each fresh formal/demo status
model in turn. Repository shell/storage implementations are never compiled or
executed. Kotlin, JSON and JUnit are taken from the existing local Gradle cache.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com/shixiaoshi0417/lunexsu"
MAIN = ROOT / "manager/app/src/main/java" / PACKAGE
TEST = ROOT / "manager/app/src/test/java" / PACKAGE
MODELS = {
    "formal": MAIN / "data/susfs/SusfsRepository.kt",
    "demo": ROOT / "manager/app/src/noRootDemo/java" / PACKAGE / "data/susfs/DemoSusfsRepository.kt",
}


def one(pattern, directory):
    matches = sorted(directory.glob(pattern))
    if not matches:
        raise RuntimeError(f"Missing cached dependency: {directory}/{pattern}")
    return matches[-1]


def check_editor_and_labels():
    source = (MAIN / "ui/screen/susfs/SusfsEditors.kt").read_text(encoding="utf-8")
    assert "val uidSchemes = SusfsInputs.redirectUidSchemes(status)" in source
    assert "SusfsInputs.validRedirect(path, destination, uid, status)" in source
    assert "uidSchemes.forEach" in source
    assert "if (uid !in uidSchemes)" in source
    assert "uid in 0..2" not in source and "SegmentedColumn { (0..2)" not in source
    assert "3 -> R.string.susfs_uid_builtin_3; 4 -> R.string.susfs_uid_builtin_4" in source
    assert 'mutableIntStateOf(rule?.optInt("uid_scheme") ?: 0)' in source
    keys = {
        "susfs_uid_selection", "susfs_uid_non_app", "susfs_uid_root", "susfs_uid_non_su",
        "susfs_uid_builtin_3", "susfs_uid_builtin_4", "susfs_uid_unsupported", "susfs_uid_hint",
    }
    for locale in ("values", "values-zh-rCN", "values-zh-rHK"):
        found = {}
        for path in (ROOT / "manager/app/src/main/res" / locale).glob("*.xml"):
            for item in ET.parse(path).getroot().findall("string"):
                key = item.get("name")
                if key in keys:
                    assert key not in found, (locale, key, "duplicate resource")
                    found[key] = "".join(item.itertext())
        assert found.keys() == keys, (locale, keys - found.keys())
        assert "0–2" not in found["susfs_uid_unsupported"]
        assert "0–4" in found["susfs_uid_hint"] and "0–2" in found["susfs_uid_hint"]
        assert found["susfs_uid_builtin_3"].startswith("3") and found["susfs_uid_builtin_4"].startswith("4")
    print("PASS: shared editor uses the tested input/selector policy; EN/zh-CN/zh-HK UID resources are complete", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default=shutil.which("java") or r"D:\Android Studio\jbr\bin\java.exe")
    parser.add_argument("--kotlin-lib", type=Path)
    parser.add_argument("--gradle-cache", type=Path, default=Path.home() / ".gradle")
    parser.add_argument("--temp", type=Path)
    parser.add_argument("--aapt2", type=Path, help="Optionally compile only the three affected SUSFS resource files")
    args = parser.parse_args()
    check_editor_and_labels()
    lib = args.kotlin_lib or one("wrapper/dists/gradle-*-bin/*/gradle-*/lib", args.gradle_cache)
    cache = args.gradle_cache / "caches/modules-2/files-2.1"
    dependencies = [one("kotlin-stdlib-*.jar", lib), one("annotations-*.jar", lib),
                    one("org.json/json/*/*/*.jar", cache), one("junit/junit/*/*/*.jar", cache),
                    one("org.hamcrest/hamcrest-core/*/*/*.jar", cache)]
    classpath = os.pathsep.join(map(str, dependencies))
    tests = [TEST / f"data/susfs/{name}.kt" for name in (
        "SusfsStatusTest", "SusfsPresentationTest", "SusfsRedirectUidTest",
    )]
    with tempfile.TemporaryDirectory(prefix="lunexsu-susfs-uid-", dir=args.temp) as tmp:
        output = Path(tmp)
        if args.aapt2:
            resources = output / "resources"
            resources.mkdir()
            files = [ROOT / "manager/app/src/main/res" / locale / "susfs.xml"
                     for locale in ("values", "values-zh-rCN", "values-zh-rHK")]
            subprocess.run([str(args.aapt2), "compile", "-o", str(resources), *map(str, files)], check=True)
            print("PASS: targeted AAPT2 compilation of the three SUSFS resource files", flush=True)
        for variant, path in MODELS.items():
            # Use the complete actual data-class body, not a mock status model or changed API.
            source = path.read_text(encoding="utf-8")
            start = source.index("data class SusfsStatus(")
            end = source.index("\n/**", start)
            model = output / f"{variant}_SusfsStatus.kt"
            model.write_text("package com.shixiaoshi0417.lunexsu.data.susfs\nimport org.json.JSONObject\n" +
                             source[start:end], encoding="utf-8")
            classes = output / variant
            subprocess.run([
                args.java, "-cp", str(lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
                "-d", str(classes), str(MAIN / "data/susfs/SusfsPresentation.kt"), str(model), *map(str, tests),
            ], check=True)
            print(f"Running fresh {variant} status model with production inputs and tests", flush=True)
            subprocess.run([
                args.java, f"-Djava.io.tmpdir={output}", "-cp", str(classes) + os.pathsep + classpath,
                "org.junit.runner.JUnitCore", *[f"com.shixiaoshi0417.lunexsu.data.susfs.{path.stem}" for path in tests],
            ], check=True)


if __name__ == "__main__":
    main()
