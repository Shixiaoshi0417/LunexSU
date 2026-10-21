"""Exercise the real synchronous Sui callback and pending-counter methods without Android."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / "userspace/sui/module/src/main/java/rikka/sui/server/SuiService.java"
JAVA_HOME = Path(os.environ.get("JAVA_HOME", "D:/Android Studio/jbr"))
JAVA = shutil.which("java") or str(JAVA_HOME / "bin/java.exe")
JAVAC = shutil.which("javac") or str(JAVA_HOME / "bin/javac.exe")


def method(source, signature):
    """Compile these production method bodies, rather than a copy of their algorithm."""
    assert source.count(signature) == 1, signature
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


class PermissionCallbackTests(unittest.TestCase):
    def run_server(self, body):
        source = SERVER.read_text(encoding="utf-8")
        production = "\n".join(method(source, signature) for signature in (
            "private static String buildPermissionRequestKey(",
            "private void markPendingPermissionConfirmation(",
            "private boolean consumePendingPermissionConfirmation(",
            "public void showPermissionConfirmation(",
        ))
        # Keep the actual pending-consumption guard; replace only its Android side effects.
        finish = method(source, "private void finishPermissionConfirmation(")
        production += "\n" + finish[:finish.index("        LOGGER.i(")] + (
            "        check(!allowed && onetime && !isShell && consumePending);\n"
            "        denials++;\n    }\n"
        )
        harness = r'''
import java.util.HashMap;
import java.util.Map;
public class Check {
    @interface NonNull {}
    interface IShizukuApplication {
        void showPermissionConfirmation(int uid, int pid, String name, int code) throws Throwable;
    }
    static final class ClientRecord { final String packageName = "com.example.client"; }
    static final class Logger {
        void w(Throwable error, String message) {}
        void w(String message, Object... args) {}
        void i(String message) {}
        void e(String message) {}
    }
    static final class RootBridgeDelegate {
        static void delegatePermissionConfirmationToRoot(Object manager, int code, String name, int uid, int pid) {
            throw new AssertionError("Unexpected delegation");
        }
    }
    static final Logger LOGGER = new Logger();
    final Object pendingPermissionLock = new Object();
    final Map<String, Integer> pendingPermissionConfirmations = new HashMap<>();
    final Object clientManager = new Object();
    boolean shellMode;
    IShizukuApplication systemUiApplication;
    int denials;
    static void check(boolean value) { if (!value) throw new AssertionError(); }
''' + production + "\npublic static void main(String[] args) {\n" + body + "\n}\n}"
        with tempfile.TemporaryDirectory(prefix="sui-permission-callback-") as temporary:
            folder = Path(temporary)
            java = folder / "Check.java"
            java.write_text(harness, encoding="utf-8")
            subprocess.run([JAVAC, "-encoding", "UTF-8", "-Xlint:all", "-Werror", "-d", str(folder), str(java)], check=True)
            subprocess.run([JAVA, "-cp", str(folder), "Check"], check=True)

    def test_manager_callback_is_synchronous_on_both_sides(self):
        paths = (
            ROOT / "manager/app/src/main/aidl/moe/shizuku/server/IShizukuApplication.aidl",
            ROOT / "userspace/sui/api/aidl/src/main/aidl/moe/shizuku/server/IShizukuApplication.aidl",
        )
        for path in paths:
            declaration = re.search(r"(?m)^\s*(oneway\s+)?void\s+showPermissionConfirmation\(", path.read_text(encoding="utf-8"))
            self.assertIsNotNone(declaration, str(path))
            self.assertIsNone(declaration.group(1), "Rejection must be observable by Sui synchronously")

    def test_each_rejected_callback_is_consumed_and_denied_locally(self):
        self.run_server(r'''
Check server = new Check();
server.systemUiApplication = (uid, pid, name, code) -> {
    throw new IllegalStateException("Manager queue full, identity rejected or old connection");
};
ClientRecord client = new ClientRecord();
for (int i = 0; i < 512; i++) server.showPermissionConfirmation(i % 3, client, 10123, 1234, 0);
check(server.denials == 512);
check(server.pendingPermissionConfirmations.isEmpty());
''')

    def test_duplicate_denials_leave_exactly_one_original_reply_pending(self):
        self.run_server(r'''
Check server = new Check();
int[] callbacks = {0};
server.systemUiApplication = (uid, pid, name, code) -> {
    if (++callbacks[0] > 1) throw new IllegalStateException("Duplicate Manager request");
};
ClientRecord client = new ClientRecord();
for (int i = 0; i < 3; i++) server.showPermissionConfirmation(7, client, 10123, 1234, 0);
check(server.denials == 2);
check(server.pendingPermissionConfirmations.get(buildPermissionRequestKey(10123, 1234, 7)) == 1);
check(server.consumePendingPermissionConfirmation(10123, 1234, 7));
check(!server.consumePendingPermissionConfirmation(10123, 1234, 7));
check(server.pendingPermissionConfirmations.isEmpty());
''')

    def test_rejection_does_not_consume_an_unrelated_accepted_request(self):
        self.run_server(r'''
Check server = new Check();
ClientRecord client = new ClientRecord();
server.systemUiApplication = (uid, pid, name, code) -> {};
server.showPermissionConfirmation(7, client, 10123, 1234, 0);
server.showPermissionConfirmation(8, client, 10123, 1234, 0);
server.systemUiApplication = (uid, pid, name, code) -> { throw new IllegalStateException("Duplicate"); };
server.showPermissionConfirmation(7, client, 10123, 1234, 0);
check(server.denials == 1);
check(server.pendingPermissionConfirmations.size() == 2);
check(server.consumePendingPermissionConfirmation(10123, 1234, 7));
check(server.consumePendingPermissionConfirmation(10123, 1234, 8));
check(server.pendingPermissionConfirmations.isEmpty());
''')


if __name__ == "__main__":
    unittest.main()
