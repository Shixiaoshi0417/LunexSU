"""Source guards complement compiled tests; these are not Android/Binder runtime tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "manager/app/src/main/java/com/shixiaoshi0417/lunexsu"
RUST = ROOT / "userspace/ksud/src"


class LifecycleWiringTests(unittest.TestCase):
    def test_only_the_unlocked_main_process_hosts_permissions(self):
        source = (KOTLIN / "LunexSUApplication.kt").read_text(encoding="utf-8")
        self.assertLess(source.index("if (!isUserUnlocked())"), source.index("SuiManager.start(this)"))
        self.assertIn("if (getProcessName() == applicationInfo.processName)", source)
        self.assertIn("SuiManager.onForeground()", (KOTLIN / "ui/MainActivity.kt").read_text(encoding="utf-8"))

    def test_request_dispatch_rechecks_identity_epoch_and_deadline(self):
        source = (KOTLIN / "data/sui/SuiManager.kt").read_text(encoding="utf-8")
        reply = source.split("private fun reply(", 1)[1].split("private fun loadApps", 1)[0]
        self.assertIn("current.epoch == request.connection", reply)
        self.assertIn("fingerprint(listOf(info)) == request.identity", reply)
        self.assertIn("session?.epoch == current.epoch", reply)
        self.assertIn("request.expired(SystemClock.elapsedRealtime())) SuiDecision.DENY_ONCE", reply)
        self.assertNotIn("newSingleThreadExecutor", source)
        self.assertIn("SynchronousQueue()", source)

    def test_demo_has_no_real_broker_or_root_access(self):
        path = ROOT / "manager/app/src/noRootDemo/java/com/shixiaoshi0417/lunexsu/data/sui/SuiManager.kt"
        source = path.read_text(encoding="utf-8")
        for forbidden in ("moe.shizuku", "android.os.Binder", "submitRootShell", "getKsuDaemonPath",
                          "ProcessBuilder", "Runtime.getRuntime", "java.io.File", "HiddenApiBypass"):
            self.assertNotIn(forbidden, source)
        gradle = (ROOT / "manager/app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('"**/data/sui/SuiManager.kt"', gradle)
        self.assertIn('"moe/shizuku/**"', gradle)

    def test_permission_callback_is_manager_only_and_death_safe(self):
        path = ROOT / "userspace/sui/module/src/main/java/rikka/sui/server/SuiService.java"
        source = path.read_text(encoding="utf-8")
        self.assertIn("if (isLunexsuManager)", source)
        self.assertIn("private volatile IShizukuApplication systemUiApplication", source)
        self.assertIn("systemUiApplication != null && systemUiApplication.asBinder() == binder", source)
        self.assertIn("if (onetime && isShell)", source)
        decision = source.split("if (onetime && isShell)", 1)[1].split("finishPermissionConfirmation", 1)[0]
        self.assertIn("allowed = false", decision)
        self.assertIn("isShell = false", decision)
        self.assertNotIn("SHELL_ONCE", (KOTLIN / "data/sui/SuiModels.kt").read_text(encoding="utf-8"))

    def test_recovery_precedes_injection_and_queries_are_bounded(self):
        source = (RUST / "init_event.rs").read_text(encoding="utf-8")
        start = source.index("crate::sui::reconcile_boot()")
        self.assertLess(start, source.index("load_sepolicy", start))
        runtime = (RUST / "sui/runtime.rs").read_text(encoding="utf-8")
        self.assertIn("deployment::owned(&live())?", runtime)
        self.assertIn("libc::PR_SET_PDEATHSIG", runtime)
        self.assertIn("libc::getppid() != parent as libc::pid_t", runtime)
        self.assertIn("failures < 5", runtime)
        queries = (RUST / "sui/mod.rs").read_text(encoding="utf-8")
        self.assertIn("runtime::wait_output(", queries)
        self.assertNotIn('.output()?', queries)


if __name__ == "__main__":
    unittest.main()
