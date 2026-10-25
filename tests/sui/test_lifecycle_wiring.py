"""Source guards complement compiled tests; these are not Android/Binder runtime tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "manager/app/src/main/java/com/shixiaoshi0417/lunexsu"
RUST = ROOT / "userspace/ksud/src"
SERVER = ROOT / "userspace/sui/module/src/main/java/rikka/sui/server/SuiService.java"
SUI_UI = ROOT / "userspace/sui/ui/src/main/java/rikka/sui"


class LifecycleWiringTests(unittest.TestCase):
    def test_only_the_unlocked_main_process_manages_sui(self):
        source = (KOTLIN / "LunexSUApplication.kt").read_text(encoding="utf-8")
        self.assertLess(source.index("if (!isUserUnlocked())"), source.index("SuiManager.start(this)"))
        self.assertIn("if (getProcessName() == applicationInfo.processName)", source)
        self.assertIn("SuiManager.onForeground()", (KOTLIN / "ui/MainActivity.kt").read_text(encoding="utf-8"))

    def test_manager_ipc_is_bounded_and_never_hosts_requests(self):
        source = (KOTLIN / "data/sui/SuiManager.kt").read_text(encoding="utf-8")
        self.assertNotIn("newSingleThreadExecutor", source)
        worker = (KOTLIN / "data/sui/SuiIpcExecutor.kt").read_text(encoding="utf-8")
        self.assertIn("SynchronousQueue()", worker)
        self.assertIn("retire(worker)", worker)
        self.assertIn("workers.size < maxWorkers", worker)
        self.assertIn("catch (error: TimeoutException)", source)
        self.assertIn("disconnect() // Invalidates", source)
        # Requests belong to Sui's SystemUI dialog; the Manager only switches and edits grants.
        for forbidden in ("attachApplication", "IShizukuApplication", "showPermissionConfirmation",
                          "dispatchPermissionConfirmationResult", "NotificationManager"):
            self.assertNotIn(forbidden, source)
        self.assertNotIn("SuiPermissionPrompt", (KOTLIN / "ui/MainActivity.kt").read_text(encoding="utf-8"))
        self.assertNotIn("SuiRequestQueue", (KOTLIN / "data/sui/SuiModels.kt").read_text(encoding="utf-8"))
        edit = source.split("fun setPermission(", 1)[1].split("private fun operation(", 1)[0]
        self.assertIn("check(fingerprint(current) == app.identity)", edit)
        self.assertIn("check(session?.epoch == currentSession.epoch)", edit)

    def test_systemui_dialog_binds_its_reply_to_the_server_identity_and_deadline(self):
        dialog = (SUI_UI / "permission/ConfirmationDialog.java").read_text(encoding="utf-8")
        show = dialog.split("public void show(", 1)[1].split("private static final class Reply", 1)[0]
        self.assertIn("BridgeServiceClient.getUidIdentity(service, requestUid)", show)
        self.assertIn("RequestIdentity.parse(", show)
        self.assertLess(show.index("RequestIdentity.parse("), show.index("getMainHandler().post("))
        self.assertIn('throw new IllegalStateException("Cannot verify the requesting UID", e)', show)
        reply = dialog.split("private static final class Reply", 1)[1].split("private void showInternal(", 1)[0]
        self.assertIn("if (!sent.compareAndSet(false, true)) return;", reply)
        self.assertIn("data.putString(REQUEST_PERMISSION_REPLY_IDENTITY, identity.fingerprint)", reply)
        self.assertIn("service.dispatchPermissionConfirmationResult(", reply)
        self.assertNotIn("getService()", reply)
        self.assertNotIn("setResult(", dialog)
        detached = dialog.split("public void onViewDetachedFromWindow(View v) {", 1)[1].split("}", 1)[0]
        self.assertIn("reply.send(false, true, false);", detached)
        self.assertIn("postDelayed(timeout, REQUEST_TIMEOUT_MS)", dialog)
        self.assertIn("private static final long REQUEST_TIMEOUT_MS = 60 * 1000L;", dialog)
        self.assertIn("if (root.getParent() == null) {", dialog)
        server = SERVER.read_text(encoding="utf-8")
        self.assertIn("private static final long PERMISSION_CONFIRMATION_TIMEOUT_MS = 75 * 1000L;", server)
        guard = server.split("if (allowed && pending != null) {", 1)[1].split("LOGGER.i(", 1)[0]
        self.assertIn("pending.expired()", guard)
        self.assertIn("!pending.identity.equals(currentIdentity)", guard)
        self.assertIn("expectedIdentity != null && !expectedIdentity.equals(currentIdentity)", guard)
        constants = (ROOT / "userspace/sui/module/src/main/java/rikka/sui/server/ServerConstants.java").read_text()
        self.assertIn('REQUEST_PERMISSION_REPLY_IDENTITY = "lunexsu:permission-identity"', constants)
        self.assertIn('REQUEST_PERMISSION_REPLY_IDENTITY = "lunexsu:permission-identity"', dialog)

    def test_shared_uid_identity_is_complete_and_visible_in_the_sui_dialog(self):
        identity = (ROOT / "userspace/sui/module/src/main/java/rikka/sui/server/UidIdentity.java").read_text()
        self.assertIn("getPackagesForUidNoThrow(uid)", identity)
        self.assertIn("getPackageInfoNoThrow(name, GET_SIGNING_CERTIFICATES, uid / 100000)", identity)
        self.assertIn("info.applicationInfo.uid != uid", identity)
        parsed = (SUI_UI / "permission/RequestIdentity.java").read_text(encoding="utf-8")
        self.assertIn('json.getInt("uid") != uid', parsed)
        self.assertIn('member.getInt("uid") != uid', parsed)
        self.assertIn("packages.contains(name)", parsed)
        self.assertIn("if (!packages.contains(requester))", parsed)
        # Mirrors UidIdentity.fingerprint(true) byte for byte, in the server's member/signer order.
        self.assertIn(".append(lastUpdate).append('|').append(TextUtils.join(\", \", digests))", parsed)
        self.assertIn("text.append(info.packageName).append('|').append(uid).append('|')", identity)
        self.assertIn('text.append(android.text.TextUtils.join(", ", certificates.get(i)))', identity)
        dialog = (SUI_UI / "permission/ConfirmationDialog.java").read_text(encoding="utf-8")
        self.assertIn("R.string.permission_shared_uid, identity.packages.size()", dialog)
        self.assertIn("for (String name : identity.packages)", dialog)
        self.assertIn("binding.details.setText(details)", dialog)
        layout = (ROOT / "userspace/sui/ui/src/main/res/layout/confirmation_dialog.xml").read_text(encoding="utf-8")
        self.assertLess(layout.index("androidx.core.widget.NestedScrollView"), layout.index('android:id="@+id/details"'))
        for locale in ("values", "values-zh-rCN", "values-zh-rTW", "values-ja"):
            strings = (ROOT / "userspace/sui/ui/src/main/res" / locale / "strings.xml").read_text(encoding="utf-8")
            self.assertIn('name="permission_shared_uid"', strings)

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

    def test_permission_callback_is_systemui_only_and_death_safe(self):
        source = SERVER.read_text(encoding="utf-8")
        self.assertIn("boolean isSystemUi = callingUid == systemUiUid && systemUiPackageName.equals(requestPackageName);",
                      source)
        attach = source.split("if (isSystemUi) {", 1)[1].split("}", 1)[0]
        self.assertIn("if (!attachPermissionHost(application, callingPid)) return;", attach)
        self.assertEqual(1, source.count("attachPermissionHost(application, callingPid)"))
        self.assertIn("private volatile IShizukuApplication systemUiApplication", source)
        self.assertIn("systemUiApplication != null && systemUiApplication.asBinder() == binder", source)
        result = source.split("public void dispatchPermissionConfirmationResult(", 1)[1].split("private int getFlagsForUidInternal", 1)[0]
        self.assertIn("if (callingUid != systemUiUid) {", result)
        self.assertNotIn("LunexsuManagerGate", result)
        decision = result.split("if ((onetime && isShell) || (allowed && expectedIdentity == null)) {", 1)[1].split("}", 1)[0]
        self.assertIn("allowed = false", decision)
        self.assertIn("onetime = true", decision)
        self.assertIn("isShell = false", decision)
        self.assertNotIn("SHELL_ONCE", (KOTLIN / "data/sui/SuiModels.kt").read_text(encoding="utf-8"))
        host = (ROOT / "userspace/sui/module/src/main/java/rikka/sui/manager/ManagerProcess.java").read_text(encoding="utf-8")
        callback = host.split("public void showPermissionConfirmation(", 1)[1].split("public boolean onTransact(", 1)[0]
        self.assertIn("if (Binder.getCallingUid() != 0) {", callback)
        attach = host.split("private static void sendToService(", 1)[1].split("private static void registerListener()", 1)[0]
        self.assertLess(attach.index("suiApk = SuiApk.createForSystemUI();"), attach.index("service.attachApplication(APPLICATION, args);"))
        self.assertLess(attach.index("if (suiApk == null) {\n            LOGGER.w("), attach.index("service.attachApplication("))

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
