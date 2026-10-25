"""Exercise the production build.rs version code with real Cargo/Git caches."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class IncrementalVersionTests(unittest.TestCase):
    def setUp(self):
        self.cargo = os.environ.get("CARGO_TEST_BIN") or shutil.which("cargo")
        self.assertTrue(self.cargo, "Set CARGO_TEST_BIN or install Cargo for this regression")
        self.temp = tempfile.TemporaryDirectory(prefix="lunex-version-incremental-")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.repo = self.directory / "repo"
        # Use existing commits only: the working repository is never modified,
        # and this fixture creates neither a commit nor a remote connection.
        self.git("clone", "--shared", "--no-checkout", "--quiet", str(ROOT), str(self.repo))
        tags = self.git("-C", str(self.repo), "for-each-ref", "--format=%(refname)", "refs/tags").stdout.splitlines()
        for tag in tags:
            self.git("-C", str(self.repo), "update-ref", "-d", tag)
        self.env = dict(os.environ, PATH=str(Path(self.cargo).parent) + os.pathsep + os.environ["PATH"])

    def git(self, *args):
        return subprocess.run(["git", *args], check=True, text=True, capture_output=True)

    def fixture(self, checkout):
        crate = checkout / "version-fixture"
        (crate / "src").mkdir(parents=True)
        (crate / "Cargo.toml").write_text('[package]\nname="version-fixture"\nversion="0.0.0"\nedition="2024"\n[workspace]\n')
        real = (ROOT / "userspace/ksud/build.rs").read_text()
        functions = real[real.index("fn track_git_version()"):real.index("fn configure_bindgen()")]
        (crate / "build.rs").write_text('use std::{io, process::Command};\n' + functions + '''
fn main() {
    let (code, name) = get_git_version().unwrap();
    println!("cargo:rustc-env=VERSION_CODE={code}");
    println!("cargo:rustc-env=VERSION_NAME={name}");
}
''')
        (crate / "src/main.rs").write_text('fn main() { println!("{}|{}", env!("VERSION_CODE"), env!("VERSION_NAME")); }\n')
        return crate

    def build(self, crate):
        result = subprocess.run([self.cargo, "run", "--offline", "--manifest-path", str(crate / "Cargo.toml")],
                                cwd=crate, env=self.env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        count = self.git("-C", str(crate), "rev-list", "--count", "HEAD").stdout.strip()
        name = self.git("-C", str(crate), "describe", "--tags", "--always").stdout.strip().lstrip("v")
        self.assertEqual(result.stdout.strip(), f"{30000 + int(count)}|{name}")
        return result

    def test_loose_packed_tag_and_detached_head_refresh_without_clean(self):
        crate = self.fixture(self.repo)
        self.build(crate)
        self.assertNotIn("Compiling version-fixture", self.build(crate).stderr)
        self.git("-C", str(self.repo), "update-ref", "HEAD", "HEAD^")
        self.build(crate)
        self.git("-C", str(self.repo), "update-ref", "refs/tags/vaudit-version", "HEAD")
        self.build(crate)
        self.git("-C", str(self.repo), "pack-refs", "--all")
        self.build(crate)
        self.git("-C", str(self.repo), "update-ref", "-d", "refs/tags/vaudit-version")
        self.build(crate)
        self.git("-C", str(self.repo), "update-ref", "--no-deref", "HEAD",
                 self.git("-C", str(self.repo), "rev-parse", "HEAD^").stdout.strip())
        self.build(crate)

    def test_linked_worktree_and_shallow_boundary_are_tracked(self):
        checkout = self.directory / "linked"
        self.git("-C", str(self.repo), "worktree", "add", "--no-checkout", "--detach", str(checkout), "HEAD")
        crate = self.fixture(checkout)
        self.build(crate)
        self.git("-C", str(checkout), "update-ref", "HEAD", "HEAD^")
        self.build(crate)
        self.git("-C", str(self.repo), "update-ref", "refs/tags/vaudit-linked", self.git("-C", str(checkout), "rev-parse", "HEAD").stdout.strip())
        self.build(crate)
        (self.repo / ".git/shallow").write_text(self.git("-C", str(checkout), "rev-parse", "HEAD").stdout)
        self.build(crate)
        (self.repo / ".git/shallow").unlink()
        self.build(crate)


if __name__ == "__main__":
    unittest.main(verbosity=2)
