"""Build/release regressions with disposable files and no real credentials/devices."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import tomllib
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


bot = load("ksubot", "scripts/ksubot.py")
config = load("cargo_config", "scripts/setup_cargo_config.py")


class TelegramTests(unittest.TestCase):
    def caption(self, event, branch="main"):
        return bot.get_caption(event, "Manager", "123", branch, "https://example.invalid/run/123")

    def test_unlinked_missing_and_null_authors(self):
        for author, expected in (({"name": "External contributor"}, "External contributor"),
                                 ({}, "unknown author"), (None, "unknown author"),
                                 ({"username": "", "name": "Local"}, "Local")):
            with self.subTest(author=author):
                self.assertIn("by " + expected, self.caption({"commits": [{"message": "Fix", "author": author}]}))

    def test_head_commit_dispatch_and_null_head(self):
        self.assertIn("Head fix", self.caption({"head_commit": {"message": "Head fix", "url": "https://example.invalid/commit"}}))
        for event in ({}, {"head_commit": None}, {"commits": [], "head_commit": None}):
            self.assertIn("Workflow run", self.caption(event))

    def test_caption_limit_includes_dev_warning(self):
        event = {"commits": [{"message": "A" * 100, "author": {"name": "B" * 100}}] * 10,
                 "compare": "https://example.invalid/" + "a" * 600}
        caption = self.caption(event, "dev")
        self.assertLessEqual(len(caption.encode("utf-16-le")) // 2, 1024)
        self.assertIn("DEV VERSION", caption)
        self.assertIn("Workflow run", caption)

    def test_upload_failures_exit_nonzero_without_printing_exception_secrets(self):
        async def failed_upload():
            raise ConnectionError("synthetic-token-never-print")
        errors = io.StringIO()
        with patch.object(bot, "main", failed_upload), contextlib.redirect_stderr(errors):
            self.assertEqual(bot.run(), 1)
        self.assertIn("ConnectionError", errors.getvalue())
        self.assertNotIn("synthetic-token-never-print", errors.getvalue())

    def test_success_exits_zero(self):
        async def uploaded():
            return None
        with patch.object(bot, "main", uploaded):
            self.assertEqual(bot.run(), 0)


class CargoConfigTests(unittest.TestCase):
    def test_ndk_paths_roundtrip_toml_and_bindgen_word_parser(self):
        for root in (Path('/opt/Android SDK/ndk/29'), Path("/opt/SDK's tools/ndk/29"),
                     Path('C:\\Users\\First Last\\Android\\ndk\\29')):
            with self.subTest(root=root), patch.object(Path, "is_file", return_value=True):
                result = tomllib.loads(config.render_config(root, "windows-x86_64", 26))
                sysroot = str(root / "toolchains/llvm/prebuilt/windows-x86_64/sysroot").replace("\\", "/")
                for arch in ("aarch64", "x86_64"):
                    args = result["env"][f"BINDGEN_EXTRA_CLANG_ARGS_{arch}_linux_android"]
                    self.assertEqual(shlex.split(args), [f"--sysroot={sysroot}", f"-I{sysroot}/usr/include/{arch}-linux-android"])

    def test_ci_shell_setup_preserves_spaces_and_quotes(self):
        env = dict(os.environ, ANDROID_NDK_HOME="/opt/Android SDK's NDK")
        script = 'source "$1" aarch64-linux-android 26\n' \
                 'python3 -c \'import os; print(os.environ["BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android"])\''
        result = subprocess.run(["bash", "-eu", "-c", script, "fixture", str(ROOT / ".github/scripts/setup-rust-build.sh")],
                                env=env, text=True, capture_output=True, check=True)
        expected = env["ANDROID_NDK_HOME"] + "/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
        self.assertEqual(shlex.split(result.stdout), ["--sysroot=" + expected, "-I" + expected + "/usr/include/aarch64-linux-android"])


class KernelBatchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lunex-build-all-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.kernel = self.root / "kernel"
        self.kernel.mkdir()
        shutil.copyfile(ROOT / "kernel/build-all.sh", self.kernel / "build-all.sh")
        scripts = self.root / ".github/scripts"
        scripts.mkdir(parents=True)
        (scripts / "check-lkm-version.sh").write_text('grep -qx "current version" "$1"\n')
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.executable("llvm-strip", "#!/bin/sh\nexit 0\n")
        self.executable("ddk", '''#!/bin/bash
set -eu
out=${3#ODIR=}
case "$2" in
  *fail*) exit 1 ;;
  *missing*) exit 0 ;;
  *wrong*) mkdir -p "$out"; echo 'old version' > "$out/kernelsu.ko" ;;
  *) mkdir -p "$out"; echo 'current version' > "$out/kernelsu.ko" ;;
esac
''')
        (self.kernel / ".ddk-version").write_text("fixture original")

    def executable(self, name, source):
        path = self.bin / name
        path.write_text(source)
        path.chmod(0o755)

    def run_batch(self, kmis):
        return subprocess.run(["bash", "build-all.sh", kmis], cwd=self.kernel,
                              env=dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"]),
                              capture_output=True, text=True)

    def test_failed_and_missing_builds_cannot_reuse_previous_modules(self):
        for kmi in ("android-fail", "android-missing"):
            (self.kernel / f"kernelsu-{kmi}.ko").write_text("old public module")
            output = self.kernel / "out" / kmi
            output.mkdir(parents=True)
            (output / "kernelsu.ko").write_text("old ddk module")
        result = self.run_batch("android-fail android-missing android-good")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertFalse((self.kernel / "kernelsu-android-fail.ko").exists())
        self.assertFalse((self.kernel / "kernelsu-android-missing.ko").exists())
        self.assertEqual((self.kernel / "kernelsu-android-good.ko").read_text(), "current version\n")
        self.assertEqual((self.kernel / ".ddk-version").read_text(), "fixture original")

    def test_version_failure_is_not_published_and_restores_configuration(self):
        result = self.run_batch("android-wrong")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.kernel / "kernelsu-android-wrong.ko").exists())
        self.assertEqual((self.kernel / ".ddk-version").read_text(), "fixture original")

    def test_complete_success_exits_zero(self):
        result = self.run_batch("android-good")
        self.assertEqual(result.returncode, 0, result.stderr)


class SigningTransportTests(unittest.TestCase):
    def test_workflow_passes_literal_gradle_environment_properties(self):
        workflow = (ROOT / ".github/workflows/build-manager.yml").read_text()
        write_key = workflow.split("- name: Write key", 1)[1].split("- name:", 1)[0]
        self.assertNotIn("gradle.properties", write_key)
        gradle = workflow.split("- name: Build with Gradle", 1)[1].split("- name:", 1)[0]
        for name in ("KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"):
            self.assertIn(f"ORG_GRADLE_PROJECT_{name}: ${{{{ github.event_name != 'pull_request' && secrets.{name} || '' }}}}", gradle)


class AllowlistTemplateTests(unittest.TestCase):
    def test_template_record_layout_matches_real_uapi(self):
        compiler = shutil.which("cc")
        self.assertIsNotNone(compiler, "A C compiler is required for the ABI regression")
        template = (ROOT / "scripts/allowlist.bt").read_text()
        definitions = template[template.index("struct root_profile {"):template.index("// Define the main app_profile")]
        names = ("root_profile", "non_root_profile", "rp_config_t", "nrp_config_t")
        for name in sorted(names, key=len, reverse=True):
            definitions = re.sub(r"\b" + name + r"\b", "bt_" + name, definitions)
        common = template.split("typedef struct {", 1)[1].split("// Based on allow_su", 1)[0]
        source = '''#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include "uapi/app_profile.h"
typedef uint8_t byte;
typedef uint32_t uint32;
typedef uint64_t uint64;
typedef int32_t int32;
typedef int64_t int64;
'''
        for name, value in re.findall(r"^#define (KSU_\w+) (\d+)$", template, re.MULTILINE):
            source += f"#undef {name}\n#define {name} {value}\n"
        source += "\n".join(f"typedef struct bt_{name} bt_{name};" for name in names) + "\n"
        source += definitions
        source += "typedef struct {" + common + "bt_rp_config_t config; } bt_root_record;\n"
        source += "typedef struct {" + common + "bt_nrp_config_t config; } bt_nonroot_record;\n"
        source += '''
_Static_assert(sizeof(bt_root_record) == sizeof(struct app_profile), "root record stride");
_Static_assert(sizeof(bt_nonroot_record) == sizeof(struct app_profile), "nonroot record stride");
_Static_assert(offsetof(bt_root_record, config.profile.flags) == offsetof(struct app_profile, rp_config.profile.flags), "root flags");
_Static_assert(offsetof(bt_nonroot_record, config.profile.umount_modules) == offsetof(struct app_profile, nrp_config.profile.umount_modules), "umount offset");
int main(void) {
    struct app_profile records[2] = {0};
    records[0].version = records[1].version = KSU_APP_PROFILE_VER;
    records[0].allow_su = true;
    records[0].rp_config.profile.flags = FLAG_KSU_NO_NEW_PRIVS;
    strcpy(records[1].key, "second.fixture");
    records[1].nrp_config.profile.umount_modules = true;
    bt_root_record root;
    bt_nonroot_record nonroot;
    memcpy(&root, records, sizeof(root));
    memcpy(&nonroot, (const char *)records + sizeof(root), sizeof(nonroot));
    assert(root.config.profile.flags == FLAG_KSU_NO_NEW_PRIVS);
    assert(strcmp(nonroot.key, "second.fixture") == 0);
    assert(nonroot.config.profile.umount_modules == 1);
    return 0;
}
'''
        with tempfile.TemporaryDirectory(prefix="lunex-allowlist-abi-") as directory:
            cfile, binary = Path(directory) / "fixture.c", Path(directory) / "fixture"
            cfile.write_text(source)
            subprocess.run([compiler, "-Wall", "-Wextra", "-Werror", "-I", str(ROOT), str(cfile), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)
        self.assertIn("header.version != 4", template)
        self.assertIn("(FileSize() - 8) % 784", template)


if __name__ == "__main__":
    unittest.main(verbosity=2)
