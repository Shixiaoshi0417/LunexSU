"""Compile the production callback/ownership methods against host Binder stand-ins.

Death and concurrency are controlled here, not exercised on an Android device.
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / "userspace/sui/module/src/main/java/rikka/sui/server/SuiService.java"
JAVA_HOME = Path(os.environ.get("JAVA_HOME", "/nonexistent"))


def tool(name):
    candidate = JAVA_HOME / "bin" / (name + (".exe" if os.name == "nt" else ""))
    return str(candidate) if candidate.is_file() else shutil.which(name)


JAVA = tool("java")
JAVAC = tool("javac")

STUBS = {
    "android/os/RemoteException.java": """
package android.os;
public class RemoteException extends Exception {
    private static final long serialVersionUID = 1L;
}
""",
    "android/os/Parcel.java": """
package android.os;
public final class Parcel {
    public int value;
    public static Parcel obtain() { return new Parcel(); }
    public void writeInt(int value) { this.value = value; }
    public void recycle() {}
}
""",
    "android/os/IBinder.java": """
package android.os;
public interface IBinder {
    int FLAG_ONEWAY = 1;
    interface DeathRecipient { void binderDied(); }
    void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException;
    boolean unlinkToDeath(DeathRecipient recipient, int flags);
    boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException;
}
""",
}


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
            "private final class PendingPermissionConfirmation",
            "private static String capturePermissionIdentity(",
            "private PendingPermissionConfirmation markPendingPermissionConfirmation(",
            "private PendingPermissionConfirmation consumePendingPermissionConfirmation(",
            "private boolean removePendingPermissionConfirmation(",
            "private void removePendingPermissionConfirmationsForUid(",
            "private void denyPermissionConfirmation(",
            "private void onPermissionHostDied(",
            "private boolean attachPermissionHost(",
            "public void showPermissionConfirmation(",
            "private final class DelegatedPermissionCallback",
            "private void dispatchDelegatedPermissionResult(",
            "private void sendDelegatedPermissionResult(",
            "private void removeDelegatedPermissionCallback(",
            "private void destroyDelegatedPermissionCallback(",
            "private boolean putDelegatedPermissionCallback(",
        ))
        production = production.replace("public void showPermissionConfirmation(", "public void showInternal(")
        # Compile the actual post-validation shell transaction path, including registration
        # and its early return. Calling the registry and show methods separately misses bugs.
        delegate = source[source.index("            String key = buildPermissionRequestKey(reqUid, reqPid, requestCode);"):]
        delegate = delegate[:delegate.index("\n        }\n        return super.onTransact")]
        production += ("\nprivate boolean delegate(int requestCode, String packageName, int reqUid, int reqPid, "
                       "IBinder callback) {\n" + delegate + "\n}\n")
        # Keep the actual reply-consumption guard; model the unchanged policy side effects.
        finish = method(source, "private void finishPermissionConfirmation(")
        production += "\n" + finish[:finish.index("        LOGGER.i(")] + (
            "        check(onetime && !isShell);\n"
            "        if (pending != null) {\n"
            "            pending.client.allowed = allowed;\n"
            "            pending.client.onetime = allowed;\n"
            "            pending.client.dispatchRequestPermissionResult(requestCode, allowed);\n"
            "            dispatchDelegatedPermissionResult(pending.key, pending.delegatedCallback, allowed);\n"
            "        } else { check(!allowed && !consumePending); noHostDenials++; }\n    }\n"
        )
        harness = r'''
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
public class Check {
    @interface NonNull {}
    interface IShizukuApplication {
        IBinder asBinder();
        void showPermissionConfirmation(int uid, int pid, String name, int code) throws Throwable;
    }
    interface Callback { void show(int uid, int pid, String name, int code) throws Throwable; }
    static class TestBinder implements IBinder {
        final List<DeathRecipient> recipients = new ArrayList<>();
        final List<Integer> results = new ArrayList<>();
        boolean alive = true;
        boolean failTransact;
        public synchronized void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
            if (!alive) throw new RemoteException();
            recipients.add(recipient);
        }
        public synchronized boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            return recipients.remove(recipient);
        }
        public synchronized boolean transact(int code, Parcel data, Parcel reply, int flags) {
            check(code == 1 && reply == null && flags == FLAG_ONEWAY);
            if (failTransact) throw new IllegalStateException("Callback delivery failed");
            results.add(data.value);
            return true;
        }
        void die() {
            List<DeathRecipient> copy;
            synchronized (this) { alive = false; copy = new ArrayList<>(recipients); }
            for (DeathRecipient recipient : copy) recipient.binderDied();
        }
    }
    static final class Host extends TestBinder implements IShizukuApplication {
        final Callback callback;
        Host(Callback callback) { this.callback = callback; }
        public IBinder asBinder() { return this; }
        public void showPermissionConfirmation(int uid, int pid, String name, int code) throws Throwable {
            callback.show(uid, pid, name, code);
        }
    }
    static final class ClientRecord {
        final String packageName = "com.example.client";
        Object client = new Object();
        ClientRecord() {}
        ClientRecord(int uid, int pid, Object client, String name, int version) { this.client = client; }
        boolean allowed, onetime;
        final List<Boolean> results = new ArrayList<>();
        final List<Integer> codes = new ArrayList<>();
        Runnable onResult = () -> {};
        synchronized void dispatchRequestPermissionResult(int code, boolean allowed) {
            results.add(allowed); codes.add(code); onResult.run();
        }
    }
    static final class Logger {
        void w(Throwable error, String message) {}
        void w(String message, Object... args) {}
        void i(String message, Object... args) {}
        void e(String message) {}
    }
    static final class Handler {
        void removeCallbacks(Runnable callback) {}
        void postDelayed(Runnable callback, long delay) {}
    }
    static final class UserHandleCompat {
        static int getUserId(int uid) { return uid / 100000; }
    }
    static final class RootBridgeDelegate {
        static void delegatePermissionConfirmationToRoot(Object manager, int code, String name, int uid, int pid) {
            throw new AssertionError("Unexpected delegation");
        }
    }
    static final Logger LOGGER = new Logger();
    static final long DELEGATED_PERMISSION_CALLBACK_TIMEOUT_MS = 300000;
    final Object managerBinderLock = new Object();
    final Map<String, List<PendingPermissionConfirmation>> pendingPermissionConfirmations = new HashMap<>();
    final java.util.concurrent.ConcurrentHashMap<String, DelegatedPermissionCallback>
            delegatedPermissionCallbacks = new java.util.concurrent.ConcurrentHashMap<>();
    static final class UidIdentity {
        static String current = "install-one";
        final String value = current;
        static UidIdentity capture(int uid) { return new UidIdentity(); }
        String binding() { return value; }
        String consentFingerprint() { return value; }
    }
    static final class Clients {
        final java.util.concurrent.ConcurrentHashMap<String, ClientRecord> records = new java.util.concurrent.ConcurrentHashMap<>();
        ClientRecord findClient(int uid, int pid) { return records.get(uid + ":" + pid); }
    }
    final Clients clientManager = new Clients();
    void showPermissionConfirmation(int code, ClientRecord client, int uid, int pid, int user) {
        if (client.client != null) clientManager.records.put(uid + ":" + pid, client);
        showInternal(code, client, uid, pid, user);
    }
    final Handler mainHandler = new Handler();
    boolean shellMode;
    volatile IShizukuApplication systemUiApplication;
    int noHostDenials;
    Host host(Callback callback) {
        Host host = new Host(callback);
        check(attachPermissionHost(host, 3000));
        return host;
    }
    void reply(int uid, int pid, int code) {
        reply(uid, pid, code, true);
    }
    void reply(int uid, int pid, int code, boolean allowed) {
        finishPermissionConfirmation(uid, pid, code, allowed, true, false, true, null);
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
    static final class Task extends Thread {
        final Runnable action;
        Throwable failure;
        Task(Runnable action) { this.action = action; }
        public void run() { try { action.run(); } catch (Throwable e) { failure = e; } }
        void finish() throws InterruptedException {
            join(5000); check(!isAlive());
            if (failure != null) throw new AssertionError(failure);
        }
    }
''' + production + "\npublic static void main(String[] args) throws Exception {\n" + body + "\n}\n}"
        with tempfile.TemporaryDirectory(prefix="sui-permission-callback-") as temporary:
            folder = Path(temporary)
            java = folder / "Check.java"
            java.write_text(harness, encoding="utf-8")
            sources = [str(java)]
            for name, text in STUBS.items():
                path = folder / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding="utf-8")
                sources.append(str(path))
            subprocess.run([JAVAC, "-encoding", "UTF-8", "-Xlint:all", "-Werror", "-d", str(folder), *sources], check=True, timeout=60)
            subprocess.run([JAVA, "-cp", str(folder), "Check"], check=True, timeout=30)

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
server.host((uid, pid, name, code) -> {
    throw new IllegalStateException("Manager queue full, identity rejected or old connection");
});
ClientRecord client = new ClientRecord();
for (int i = 0; i < 512; i++) server.showPermissionConfirmation(i % 3, client, 10123, 1234, 0);
check(client.results.size() == 512 && !client.results.contains(true));
check(server.pendingPermissionConfirmations.isEmpty());
''')

    def test_duplicate_denials_leave_exactly_one_original_reply_pending(self):
        self.run_server(r'''
Check server = new Check();
int[] callbacks = {0};
server.host((uid, pid, name, code) -> {
    if (++callbacks[0] > 1) throw new IllegalStateException("Duplicate Manager request");
});
ClientRecord client = new ClientRecord();
for (int i = 0; i < 3; i++) server.showPermissionConfirmation(7, client, 10123, 1234, 0);
check(client.results.equals(List.of(false, false)));
check(server.pendingPermissionConfirmations.get(buildPermissionRequestKey(10123, 1234, 7)).size() == 1);
server.reply(10123, 1234, 7);
server.reply(10123, 1234, 7);
check(client.results.equals(List.of(false, false, true)));
check(server.pendingPermissionConfirmations.isEmpty());
''')

    def test_rejection_does_not_consume_an_unrelated_accepted_request(self):
        self.run_server(r'''
Check server = new Check();
ClientRecord client = new ClientRecord();
server.host((uid, pid, name, code) -> {});
server.showPermissionConfirmation(7, client, 10123, 1234, 0);
server.showPermissionConfirmation(8, client, 10123, 1234, 0);
server.host((uid, pid, name, code) -> { throw new IllegalStateException("Duplicate"); });
server.showPermissionConfirmation(7, client, 10123, 1234, 0);
check(client.results.equals(List.of(false)));
check(server.pendingPermissionConfirmations.size() == 2);
server.reply(10123, 1234, 7);
server.reply(10123, 1234, 8);
check(client.results.equals(List.of(false, true, true)));
check(server.pendingPermissionConfirmations.isEmpty());
''')

    def test_manager_death_denies_all_admitted_requests_once_without_revoking_grants(self):
        self.run_server(r'''
Check server = new Check();
Host host = server.host((uid, pid, name, code) -> {});
ClientRecord client = new ClientRecord();
client.allowed = client.onetime = true;
client.onResult = () -> check(!Thread.holdsLock(server.managerBinderLock));
for (int code : new int[] {7, 7, 8}) server.showPermissionConfirmation(code, client, 10123, 1234, 0);
host.die();
host.die();
server.reply(10123, 1234, 7);
check(server.systemUiApplication == null);
check(client.codes.size() == 3 && java.util.Collections.frequency(client.codes, 7) == 2);
check(client.results.equals(List.of(false, false, false)));
check(client.allowed && client.onetime);
check(server.pendingPermissionConfirmations.isEmpty());
check(host.recipients.isEmpty());
server.showPermissionConfirmation(9, client, 10123, 1234, 0);
check(server.noHostDenials == 1 && server.pendingPermissionConfirmations.isEmpty());
''')

    def test_live_callback_replacement_preserves_requests_until_reply_or_owner_death(self):
        self.run_server(r'''
Check server = new Check();
ClientRecord client = new ClientRecord();
Host old = server.host((uid, pid, name, code) -> {});
server.showPermissionConfirmation(7, client, 10123, 1234, 0);
Host current = server.host((uid, pid, name, code) -> {});
check(client.results.isEmpty());
server.reply(10123, 1234, 7);
old.die();
check(server.systemUiApplication == current);
check(client.results.equals(List.of(true)));
check(client.allowed && client.onetime);
check(server.pendingPermissionConfirmations.isEmpty());
''')

    def test_old_host_death_cannot_consume_new_host_request_with_same_key(self):
        self.run_server(r'''
Check server = new Check();
ClientRecord oldClient = new ClientRecord(), newClient = new ClientRecord();
Host old = server.host((uid, pid, name, code) -> {});
server.showPermissionConfirmation(7, oldClient, 10123, 1234, 0);
Host current = server.host((uid, pid, name, code) -> {});
server.showPermissionConfirmation(7, newClient, 10123, 1234, 0);
old.die();
check(server.systemUiApplication == current);
check(oldClient.results.equals(List.of(false)) && newClient.results.isEmpty());
server.reply(10123, 1234, 7);
current.die();
check(newClient.results.equals(List.of(true)));
check(server.pendingPermissionConfirmations.isEmpty());
''')

    def test_death_during_delivery_and_late_failure_do_not_consume_replacement(self):
        self.run_server(r'''
Check server = new Check();
ClientRecord oldClient = new ClientRecord(), newClient = new ClientRecord();
CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
Host old = server.host((uid, pid, name, code) -> {
    entered.countDown(); await(release); throw new RemoteException();
});
Task delivery = new Task(() -> server.showPermissionConfirmation(7, oldClient, 10123, 1234, 0));
delivery.start(); await(entered);
old.die();
Host current = server.host((uid, pid, name, code) -> {});
server.showPermissionConfirmation(7, newClient, 10123, 1234, 0);
release.countDown(); delivery.finish();
check(oldClient.results.equals(List.of(false)) && newClient.results.isEmpty());
server.reply(10123, 1234, 7);
check(newClient.results.equals(List.of(true)));
check(server.systemUiApplication == current && server.pendingPermissionConfirmations.isEmpty());
''')

    def test_reply_before_delivery_failure_is_not_denied_again(self):
        self.run_server(r'''
Check server = new Check();
ClientRecord client = new ClientRecord();
Host host = server.host((uid, pid, name, code) -> {
    server.reply(uid, pid, code); throw new RemoteException();
});
server.showPermissionConfirmation(7, client, 10123, 1234, 0);
host.die();
check(client.results.equals(List.of(true)) && client.allowed && client.onetime);
check(server.pendingPermissionConfirmations.isEmpty());
''')

    def test_reply_and_death_race_have_one_winner(self):
        self.run_server(r'''
for (int i = 0; i < 200; i++) {
    Check server = new Check();
    ClientRecord client = new ClientRecord();
    Host host = server.host((uid, pid, name, code) -> {});
    server.showPermissionConfirmation(7, client, 10123, 1234, 0);
    CountDownLatch start = new CountDownLatch(1);
    Task death = new Task(() -> { await(start); host.die(); });
    Task reply = new Task(() -> { await(start); server.reply(10123, 1234, 7); });
    death.start(); reply.start(); start.countDown();
    death.finish(); reply.finish();
    check(client.results.size() == 1 && server.pendingPermissionConfirmations.isEmpty());
}
''')

    def test_admission_and_death_race_cannot_orphan_a_request(self):
        self.run_server(r'''
for (int i = 0; i < 200; i++) {
    Check server = new Check();
    ClientRecord client = new ClientRecord();
    Host host = server.host((uid, pid, name, code) -> {});
    CountDownLatch start = new CountDownLatch(1);
    Task death = new Task(() -> { await(start); host.die(); });
    Task admission = new Task(() -> { await(start); server.showPermissionConfirmation(7, client, 10123, 1234, 0); });
    death.start(); admission.start(); start.countDown();
    death.finish(); admission.finish();
    check(client.results.size() + server.noHostDenials == 1 && !client.results.contains(true));
    check(server.systemUiApplication == null && server.pendingPermissionConfirmations.isEmpty());
}
''')

    def test_already_dead_callback_cannot_replace_live_host(self):
        self.run_server(r'''
Check server = new Check();
Host current = server.host((uid, pid, name, code) -> {});
Host dead = new Host((uid, pid, name, code) -> {});
dead.die();
check(!server.attachPermissionHost(dead, 3001));
check(server.systemUiApplication == current);
''')

    def test_manager_death_notifies_delegated_shell_request_once(self):
        self.run_server(r'''
Check server = new Check();
Host host = server.host((uid, pid, name, code) -> {});
ClientRecord dummy = new ClientRecord(); dummy.client = null;
TestBinder delegate = new TestBinder();
String key = buildPermissionRequestKey(10123, 1234, 7);
server.putDelegatedPermissionCallback(key, delegate);
server.showPermissionConfirmation(7, dummy, 10123, 1234, 0);
host.die(); host.die(); server.reply(10123, 1234, 7);
check(delegate.results.equals(List.of(0)) && delegate.recipients.isEmpty());
check(dummy.results.isEmpty());
check(server.delegatedPermissionCallbacks.isEmpty() && server.pendingPermissionConfirmations.isEmpty());
''')

    def test_old_owner_cannot_deny_replacement_delegated_callback(self):
        self.run_server(r'''
Check server = new Check();
ClientRecord dummy = new ClientRecord(); dummy.client = null;
String key = buildPermissionRequestKey(10123, 1234, 7);
Host old = server.host((uid, pid, name, code) -> {});
TestBinder oldDelegate = new TestBinder(), newDelegate = new TestBinder();
server.putDelegatedPermissionCallback(key, oldDelegate);
server.showPermissionConfirmation(7, dummy, 10123, 1234, 0);
server.host((uid, pid, name, code) -> {});
// An expired/dead callback can free its slot while the old UI request is retained.
server.removeDelegatedPermissionCallback(key, server.delegatedPermissionCallbacks.get(key));
server.putDelegatedPermissionCallback(key, newDelegate);
server.showPermissionConfirmation(7, dummy, 10123, 1234, 0);
old.die();
check(oldDelegate.results.isEmpty() && newDelegate.results.isEmpty());
check(server.delegatedPermissionCallbacks.get(key).binder == newDelegate);
server.reply(10123, 1234, 7);
check(newDelegate.results.equals(List.of(1)));
check(server.delegatedPermissionCallbacks.isEmpty() && server.pendingPermissionConfirmations.isEmpty());
''')

    def test_shell_duplicates_preserve_first_callback_and_reject_only_new_callbacks(self):
        self.run_server(r'''
Check server = new Check();
int[] prompts = {0};
server.host((uid, pid, name, code) -> {
    if (++prompts[0] > 1) throw new IllegalStateException("Duplicate Manager request");
});
TestBinder first = new TestBinder(), second = new TestBinder(), third = new TestBinder();
String key = buildPermissionRequestKey(10123, 1234, 7);
check(server.delegate(7, "com.example.client", 10123, 1234, first));
check(server.delegate(7, "com.example.client", 10123, 1234, second));
check(server.delegate(7, "com.example.client", 10123, 1234, third));
check(prompts[0] == 1 && first.results.isEmpty());
check(second.results.equals(List.of(0)) && third.results.equals(List.of(0)));
check(first.recipients.size() == 1 && second.recipients.isEmpty() && third.recipients.isEmpty());
check(server.delegatedPermissionCallbacks.get(key).binder == first);
check(server.pendingPermissionConfirmations.get(key).size() == 1);
server.reply(10123, 1234, 7);
server.reply(10123, 1234, 7);
check(first.results.equals(List.of(1)) && first.recipients.isEmpty());
check(second.results.equals(List.of(0)) && third.results.equals(List.of(0)));
check(server.delegatedPermissionCallbacks.isEmpty() && server.pendingPermissionConfirmations.isEmpty());
''')

    def test_concurrent_shell_duplicates_admit_only_one_callback(self):
        self.run_server(r'''
for (int i = 0; i < 200; i++) {
    Check server = new Check();
    java.util.concurrent.atomic.AtomicInteger prompts = new java.util.concurrent.atomic.AtomicInteger();
    server.host((uid, pid, name, code) -> { prompts.incrementAndGet(); });
    TestBinder first = new TestBinder(), second = new TestBinder();
    CountDownLatch start = new CountDownLatch(1);
    Task a = new Task(() -> { await(start); check(server.delegate(7, "com.example.client", 10123, 1234, first)); });
    Task b = new Task(() -> { await(start); check(server.delegate(7, "com.example.client", 10123, 1234, second)); });
    a.start(); b.start(); start.countDown(); a.finish(); b.finish();
    check(prompts.get() == 1);
    server.reply(10123, 1234, 7);
    check(first.results.size() == 1 && second.results.size() == 1);
    check(first.results.get(0) + second.results.get(0) == 1);
    check(first.recipients.isEmpty() && second.recipients.isEmpty());
    check(server.delegatedPermissionCallbacks.isEmpty() && server.pendingPermissionConfirmations.isEmpty());
}
''')

    def test_duplicate_callback_delivery_failure_cannot_break_original_denial(self):
        self.run_server(r'''
Check server = new Check();
int[] prompts = {0};
server.host((uid, pid, name, code) -> { prompts[0]++; });
TestBinder first = new TestBinder(), duplicate = new TestBinder();
duplicate.failTransact = true;
server.delegate(7, "com.example.client", 10123, 1234, first);
server.delegate(7, "com.example.client", 10123, 1234, duplicate);
check(prompts[0] == 1 && first.results.isEmpty() && first.recipients.size() == 1);
check(duplicate.recipients.isEmpty());
server.reply(10123, 1234, 7, false);
check(first.results.equals(List.of(0)) && first.recipients.isEmpty());
check(server.pendingPermissionConfirmations.isEmpty() && server.delegatedPermissionCallbacks.isEmpty());
''')

    def test_shell_keys_are_independent_and_can_be_reused_after_completion(self):
        self.run_server(r'''
Check server = new Check();
server.host((uid, pid, name, code) -> {});
TestBinder a = new TestBinder(), b = new TestBinder(), c = new TestBinder(), next = new TestBinder();
server.delegate(7, "com.example.client", 10123, 1234, a);
server.delegate(8, "com.example.client", 10123, 1234, b);
server.delegate(7, "com.example.client", 10124, 1235, c);
server.reply(10123, 1234, 7);
server.delegate(7, "com.example.client", 10123, 1234, next);
check(next.results.isEmpty() && server.delegatedPermissionCallbacks.size() == 3);
server.reply(10123, 1234, 7, false);
server.reply(10123, 1234, 8);
server.reply(10124, 1235, 7);
check(a.results.equals(List.of(1)) && b.results.equals(List.of(1)));
check(c.results.equals(List.of(1)) && next.results.equals(List.of(0)));
check(server.pendingPermissionConfirmations.isEmpty() && server.delegatedPermissionCallbacks.isEmpty());
''')

    def test_dead_or_missing_shell_callback_is_not_admitted(self):
        self.run_server(r'''
Check server = new Check();
int[] prompts = {0};
server.host((uid, pid, name, code) -> { prompts[0]++; });
TestBinder dead = new TestBinder(); dead.die();
server.delegate(7, "com.example.client", 10123, 1234, dead);
server.delegate(7, "com.example.client", 10123, 1234, null);
check(prompts[0] == 0);
check(server.pendingPermissionConfirmations.isEmpty() && server.delegatedPermissionCallbacks.isEmpty());
''')

    def test_manager_death_after_shell_duplicate_denies_only_original_once(self):
        self.run_server(r'''
Check server = new Check();
Host host = server.host((uid, pid, name, code) -> {});
TestBinder first = new TestBinder(), duplicate = new TestBinder();
server.delegate(7, "com.example.client", 10123, 1234, first);
server.delegate(7, "com.example.client", 10123, 1234, duplicate);
host.die(); host.die(); server.reply(10123, 1234, 7);
check(first.results.equals(List.of(0)) && duplicate.results.equals(List.of(0)));
check(first.recipients.isEmpty() && duplicate.recipients.isEmpty());
check(server.pendingPermissionConfirmations.isEmpty() && server.delegatedPermissionCallbacks.isEmpty());
''')

    def test_uid_removal_keeps_other_clients_pending(self):
        self.run_server(r'''
Check server = new Check();
Host host = server.host((uid, pid, name, code) -> {});
ClientRecord removed = new ClientRecord(), other = new ClientRecord();
server.showPermissionConfirmation(7, removed, 10123, 1234, 0);
server.showPermissionConfirmation(7, other, 10124, 1235, 0);
server.removePendingPermissionConfirmationsForUid(10123);
host.die();
check(removed.results.isEmpty() && other.results.equals(List.of(false)));
check(server.pendingPermissionConfirmations.isEmpty());
''')

    def test_changed_install_or_manager_consent_cannot_grant(self):
        self.run_server(r'''
Check server = new Check();
server.host((uid, pid, name, code) -> {});
ClientRecord client = new ClientRecord();
server.showPermissionConfirmation(7, client, 10123, 1234, 0);
UidIdentity.current = "reinstalled";
server.reply(10123, 1234, 7);
server.showPermissionConfirmation(8, client, 10123, 1234, 0);
server.finishPermissionConfirmation(10123, 1234, 8, true, true, false, true, "old-prompt");
check(client.results.equals(List.of(false, false)) && !client.allowed);
check(server.pendingPermissionConfirmations.isEmpty());
''')

    def test_replaced_client_cannot_receive_old_consent(self):
        self.run_server(r'''
Check server = new Check();
server.host((uid, pid, name, code) -> {});
ClientRecord old = new ClientRecord(), replacement = new ClientRecord();
server.showPermissionConfirmation(7, old, 10123, 1234, 0);
server.clientManager.records.put("10123:1234", replacement);
server.reply(10123, 1234, 7);
check(old.results.equals(List.of(false)) && !replacement.allowed);
''')


if __name__ == "__main__":
    unittest.main()
