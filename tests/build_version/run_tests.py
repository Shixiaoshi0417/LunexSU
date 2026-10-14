"""Exercise real Kbuild version calculation and the CI artifact gate."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
CHECK = ROOT / ".github/scripts/check-lkm-version.sh"
KBUILD = (ROOT / "kernel/Kbuild").read_text()
VERSION_RULES = KBUILD.split("# Workaround bazel", 1)[1].split("KSU_NEW_DCACHE_FLUSH :=", 1)[0]


class BuildVersionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lunexsu-build-version-")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.repo = self.directory / "LunexSU"
        self.repo.mkdir()
        self.env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        self.env.update({
            "GIT_CONFIG_GLOBAL": str(self.directory / "gitconfig"),
            "GIT_CONFIG_NOSYSTEM": "1",
            "GITHUB_WORKSPACE": str(self.repo),
        })
        self.git("init", "--quiet", str(self.repo))
        for number in range(2):
            self.git("-C", str(self.repo), "-c", "user.name=Version Test",
                     "-c", "user.email=version-test@example.invalid", "commit",
                     "--quiet", "--allow-empty", "-m", f"Fixture {number}")

    def git(self, *arguments):
        return subprocess.run(["git", *arguments], env=self.env, cwd=self.directory, check=True,
                              text=True, capture_output=True)

    def compile_module(self, kernel_repo=None):
        # Compile only the version initialized by the production Kbuild rules.
        # The real kernel is compiled separately by the DDK workflow.
        source = self.directory / "version.c"
        source.write_text(
            "#define TEXT_(x) #x\n#define TEXT(x) TEXT_(x)\n"
            'const char version[] = "welcome to LunexSU version " TEXT(KSU_VERSION) '
            '", package name com.shixiaoshi0417.lunexsu";\n'
        )
        module = self.directory / "module.o"
        makefile = (
            f"MDIR := {self.repo}\nsrctree := {kernel_repo or self.directory}\n"
            + VERSION_RULES
            + f"\nall:\n\t$(CC) $(ccflags-y) -c {source} -o {module}\n"
        )
        subprocess.run(["make", "--no-print-directory", "-f", "-"], input=makefile,
                       env=self.env, text=True, capture_output=True, check=True)
        return module

    def verify(self, module):
        return subprocess.run(["bash", str(CHECK), str(module)], env=self.env,
                              text=True, capture_output=True)

    def test_full_history_compiled_version_passes(self):
        result = self.verify(self.compile_module())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Verified LKM Core version 30002", result.stdout)

    def test_wrong_fork_safe_directory_compiles_16_but_gate_rejects(self):
        self.env["GIT_TEST_ASSUME_DIFFERENT_OWNER"] = "1"
        self.git("config", "--global", "--add", "safe.directory", "/__w/KernelSU/KernelSU")
        module = self.compile_module()
        self.assertIn(b"welcome to LunexSU version 16,", module.read_bytes())
        # A later readable Git context must still reject this already-built artifact.
        self.git("config", "--global", "--add", "safe.directory", str(self.repo))
        result = self.verify(module)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("expected 30002", result.stderr)

    def test_correct_workspace_safe_directory_preserves_version(self):
        self.env["GIT_TEST_ASSUME_DIFFERENT_OWNER"] = "1"
        self.git("config", "--global", "--add", "safe.directory", str(self.repo))
        result = self.verify(self.compile_module())
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_same_kernel_repository_fallback_is_rejected(self):
        result = self.verify(self.compile_module(kernel_repo=self.repo))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("expected 30002", result.stderr)

    def test_shallow_history_is_rejected(self):
        module = self.compile_module()
        shallow = self.directory / "shallow"
        self.git("clone", "--quiet", "--depth=1", self.repo.as_uri(), str(shallow))
        self.env["GITHUB_WORKSPACE"] = str(shallow)
        result = self.verify(module)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("complete Git history", result.stderr)

    def test_missing_git_metadata_is_rejected(self):
        module = self.compile_module()
        self.env["GITHUB_WORKSPACE"] = str(self.directory)
        self.assertNotEqual(self.verify(module).returncode, 0)

    def test_missing_module_is_rejected(self):
        self.assertNotEqual(self.verify(self.directory / "missing.ko").returncode, 0)

    def test_artifact_from_different_commit_is_rejected(self):
        module = self.compile_module()
        self.git("-C", str(self.repo), "-c", "user.name=Version Test",
                 "-c", "user.email=version-test@example.invalid", "commit",
                 "--quiet", "--allow-empty", "-m", "Different build revision")
        result = self.verify(module)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("expected 30003", result.stderr)

    def test_large_strings_output_does_not_false_fail_with_sigpipe(self):
        module = self.compile_module()
        with module.open("ab") as output:
            output.write(b"\0" + b"Other module strings\0" * 65536)
        result = self.verify(module)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
