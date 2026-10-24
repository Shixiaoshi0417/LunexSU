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
        self.assertIn("uidIdentity(request.uid, request.packageName)", reply)
        self.assertIn("identity.packages == request.packages && identity.fingerprint == request.identity", reply)
        self.assertLess(reply.index("uidIdentity("), reply.index("beforeDispatch()"))
        self.assertIn("session?.epoch == current.epoch", reply)
        self.assertIn("request.expired(SystemClock.elapsedRealtime())) SuiDecision.DENY_ONCE", reply)
        self.assertNotIn("newSingleThreadExecutor", source)
        worker = (KOTLIN / "data/sui/SuiIpcExecutor.kt").read_text(encoding="utf-8")
        self.assertIn("SynchronousQueue()", worker)
        self.assertIn("retire(worker)", worker)
        self.assertIn("workers.size < maxWorkers", worker)
        self.assertIn("lease.checkActive()", reply)
        self.assertIn("catch (error: TimeoutException)", source)
        self.assertIn("disconnect() // Invalidates", source)

    def test_shared_uid_snapshot_is_complete_and_visible_in_the_common_prompt(self):
        source = (KOTLIN / "data/sui/SuiManager.kt").read_text(encoding="utf-8")
        callback = source.split("override fun showPermissionConfirmation", 1)[1].split("private fun disconnect", 1)[0]
        self.assertIn("uidIdentity(uid, name)", callback)
        self.assertIn("identity.fingerprint, identity.packages", callback)
        self.assertLess(callback.index("uidIdentity("), callback.index("requests.requireOffer(request)"))
        self.assertIn("context.packageManager.getPackagesForUid(uid)", source)
        self.assertIn("PackageManager.GET_SIGNING_CERTIFICATES", source)
        self.assertIn("info.signingInfo?.apkContentsSigners", source)
        self.assertIn("info.firstInstallTime, info.lastUpdateTime", source)
        screen = (KOTLIN / "ui/screen/sui/SuiScreen.kt").read_text(encoding="utf-8")
        prompt = screen.split("fun SuiPermissionPrompt()", 1)[1]
        self.assertIn("R.string.sui_shared_uid", prompt)
        self.assertIn("request.packages.forEach { Text(it) }", prompt)
        self.assertIn("Modifier.fillMaxWidth().verticalScroll(rememberScrollState())", prompt)
        self.assertIn("ui.screen.susfs.AlertDialog", screen)

    def test_lifecycle_timeout_retains_unknown_outcome_until_authoritative_status(self):
        source = (KOTLIN / "data/sui/SuiManager.kt").read_text(encoding="utf-8")
        self.assertIn("lifecycle.retain(future)", source)
        self.assertIn('lifecycle.reconcile { SuiStatus.parse(command("status")) }', source)
        self.assertIn("while (lifecycle.isPending())", source)
        self.assertIn("allowReconciliation || !lifecycle.isPending()", source)
        screen = (KOTLIN / "ui/screen/sui/SuiScreen.kt").read_text(encoding="utf-8")
        self.assertIn("ui.reconciling -> R.string.sui_outcome_pending", screen)
        self.assertIn("!ui.busy && !ui.reconciling", screen)

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
        self.assertIn("if (!attachPermissionHost(application, callingPid)) return;", source)
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

    def test_supervisor_probes_both_binders_and_keeps_the_restart_budget(self):
        runtime = (RUST / "sui/runtime.rs").read_text(encoding="utf-8")
        supervise = runtime.split("pub fn supervise()", 1)[1].split("pub fn boot_services()", 1)[0]
        self.assertIn("supervision::watch(", supervise)
        self.assertIn("binder_diagnostics().is_ok_and(|value| supervision::servers_alive(&value))", supervise)
        self.assertIn("can_start(false)", supervise)
        self.assertIn("super::register_manager()", supervise)
        self.assertIn("supervision::Outcome::Stopped", supervise)
        self.assertIn("failures < 5", supervise)
        self.assertIn("failures += 1", supervise)
        self.assertIn("Duration::from_secs(1_u64 << failures)", supervise)


if __name__ == "__main__":
    unittest.main()
