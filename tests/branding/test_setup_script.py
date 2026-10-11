"""kernel/setup.sh must integrate LunexSU's own Core, never an upstream KernelSU tag."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SETUP = Path(os.environ.get("LUNEXSU_SETUP_SH", ROOT / "kernel/setup.sh"))


class SetupScriptTests(unittest.TestCase):
    def setUp(self):
        self.temp = Path(tempfile.mkdtemp(prefix="lunexsu-setup-"))
        self.addCleanup(shutil.rmtree, self.temp)
        self.env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        self.env.update({
            "GIT_CONFIG_GLOBAL": str(self.temp / "gitconfig"),
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_AUTHOR_NAME": "Setup Test",
            "GIT_AUTHOR_EMAIL": "setup-test@example.invalid",
            "GIT_COMMITTER_NAME": "Setup Test",
            "GIT_COMMITTER_EMAIL": "setup-test@example.invalid",
        })
        # The fork carries upstream release tags on history older than LunexSU's main.
        work = self.temp / "work"
        self.git("init", "-q", "-b", "main", str(work))
        (work / "kernel").mkdir()
        (work / "kernel/Kbuild").write_text("KSU_PACKAGE_NAME ?= me.weishu.kernelsu\n")
        self.git("-C", str(work), "add", ".")
        self.git("-C", str(work), "commit", "-qm", "Upstream release")
        self.git("-C", str(work), "tag", "v3.3.0")
        self.upstream = self.rev(work)
        (work / "kernel/Kbuild").write_text("KSU_PACKAGE_NAME ?= com.shixiaoshi0417.lunexsu\n")
        self.git("-C", str(work), "commit", "-qam", "LunexSU")
        self.lunexsu = self.rev(work)
        remote = self.temp / "remote.git"
        self.git("clone", "-q", "--bare", str(work), str(remote))

        self.gki = self.temp / "gki"
        (self.gki / "drivers").mkdir(parents=True)
        (self.gki / "drivers/Makefile").write_text("obj-y += base/\n")
        (self.gki / "drivers/Kconfig").write_text('menu "Device Drivers"\nendmenu\n')
        # An existing checkout skips the network clone, as on every later run.
        self.git("clone", "-q", str(remote), str(self.gki / "LunexSU"))

    def git(self, *arguments):
        return subprocess.run(["git", *arguments], env=self.env, check=True, text=True, capture_output=True)

    def rev(self, repository):
        return self.git("-C", str(repository), "rev-parse", "HEAD").stdout.strip()

    def run_setup(self, *arguments):
        subprocess.run(["sh", str(SETUP), *arguments], cwd=self.gki, env=self.env, check=True,
                       text=True, capture_output=True)
        return self.rev(self.gki / "LunexSU")

    def test_default_integrates_lunexsu_main_not_the_latest_upstream_tag(self):
        self.assertEqual(self.lunexsu, self.run_setup())
        self.assertIn("com.shixiaoshi0417.lunexsu", (self.gki / "drivers/kernelsu/Kbuild").read_text())
        self.assertIn("obj-$(CONFIG_KSU) += kernelsu/", (self.gki / "drivers/Makefile").read_text())
        self.assertIn('source "drivers/kernelsu/Kconfig"', (self.gki / "drivers/Kconfig").read_text())

    def test_explicit_ref_is_honored_and_default_returns_to_main(self):
        self.assertEqual(self.upstream, self.run_setup("v3.3.0"))
        self.assertEqual(self.lunexsu, self.run_setup())


if __name__ == "__main__":
    unittest.main(verbosity=2)
