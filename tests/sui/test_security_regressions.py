"""Run production handshake, Rish lease, UserService registry and native DEX entry code.

Platform calls are host stand-ins. These verify policy/control flow, not Android integration.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from test_permission_callback import JAVA, JAVAC, ROOT, SERVER, method


def run_java(text, name="Check"):
    with tempfile.TemporaryDirectory(prefix="sui-regression-") as temporary:
        folder = Path(temporary)
        source = folder / (name + ".java")
        source.write_text(text)
        subprocess.run([JAVAC, "-Xlint:all", "-Werror", "-d", str(folder), str(source)], check=True, timeout=60)
        subprocess.run([JAVA, "-cp", str(folder), name], check=True, timeout=30)


@unittest.skipUnless(JAVA and JAVAC, "needs JDK")
class SecurityRegressions(unittest.TestCase):
    def test_identity_downgrade_revokes_capabilities_and_retries_shell_ack(self):
        source = SERVER.read_text()
        production = "\n".join(method(source, sig) for sig in (
            "private void updateFlagsForUid(int uid, int mask, int value, String expectedIdentity)",
            "private void reconcileUidIdentities(", "private void flushIdentityRevocations(",
        ))
        run_java(r'''
import java.util.*;
public class Check {
    static final boolean shellMode = false;
    static class Logger { void w(Throwable e, String s) {} } static final Logger LOGGER = new Logger();
    static class SuiConfig {
        static final int MASK_PERMISSION = 30, FLAG_DENIED = 4, FLAG_ALLOWED_SHELL = 16;
        static class PackageEntry { int uid = 10123, flags; PackageEntry(int f) { flags = f; } }
    }
    static class ClientRecord { int uid; boolean onetime, allowed; String permissionIdentity; }
    static class Clients { List<ClientRecord> getClients() { return List.of(); } }
    static class UidIdentity { static boolean matches(int u, String s) { return false; } }
    static class ClientFallback {}
    static class Config {
        SuiConfig.PackageEntry raw = new SuiConfig.PackageEntry(2); boolean diskFailure;
        SuiConfig.PackageEntry findExplicit(int uid) { return raw; }
        SuiConfig.PackageEntry find(int uid) { return new SuiConfig.PackageEntry(4); }
        void update(int uid, int mask, int value, String identity) { raw = new SuiConfig.PackageEntry(value); }
        List<SuiConfig.PackageEntry> staleGrants() { return raw.flags == 2 ? List.of(raw) : List.of(); }
        boolean denyStaleGrant(SuiConfig.PackageEntry e) {
            if (diskFailure) throw new IllegalStateException("disk");
            if (raw != e) return false; raw = new SuiConfig.PackageEntry(4); return true;
        }
    }
    final Config configManager = new Config(); final Clients clientManager = new Clients();
    final java.util.concurrent.ConcurrentHashMap<Integer, Object> identityRevocationsPendingShellSync = new java.util.concurrent.ConcurrentHashMap<>();
    int barriers, finishes, revoked, fallbackRevoked; boolean shellUnavailable;
    void enforceManagerPermission(String f) {}
    boolean requiresCurrentServerCapabilityReset(int old, int next) { return old == 2 && next != 2; }
    void beginPermissionTransition(int uid) { barriers++; } void finishPermissionTransition(int uid) { finishes++; }
    void updateClientAllowedStateForUid(int uid, int flags) {} void syncUidsToSystemServer() {}
    List<ClientFallback> flushShellRoutingState() {
        if (shellUnavailable) throw new IllegalStateException("shell unavailable");
        return List.of(new ClientFallback());
    }
    int getServerUidForPermissionFlags(int f) { return -1; }
    List<ClientFallback> handoffClientsForUid(int u, int f) { return List.of(); }
    void invalidatePackagesForUid(int uid, String reason) { revoked++; }
    void invalidateFallbacks(Collection<ClientFallback> fallbacks, String reason) { fallbackRevoked += fallbacks.size(); }
''' + production + r'''
    static void check(boolean v) { if (!v) throw new AssertionError(); }
    public static void main(String[] args) {
        Check c = new Check();
        c.updateFlagsForUid(10123, 30, 4, "consent");
        check(c.barriers == 1 && c.finishes == 1 && c.revoked == 1);
        c.configManager.raw = new SuiConfig.PackageEntry(2);
        c.configManager.diskFailure = true;
        c.reconcileUidIdentities();
        check(c.revoked == 2 && c.barriers == c.finishes);
        check(!c.identityRevocationsPendingShellSync.isEmpty());
        c.shellUnavailable = true;
        try { c.flushIdentityRevocations(); throw new AssertionError(); }
        catch (IllegalStateException expected) {}
        check(!c.identityRevocationsPendingShellSync.isEmpty());
        c.shellUnavailable = false; c.flushIdentityRevocations();
        check(c.identityRevocationsPendingShellSync.isEmpty() && c.fallbackRevoked == 1);
    }
}
''')

    def test_handshake_only_bypasses_acl_for_verified_manager_reads(self):
        sui = SERVER.read_text()
        base = (ROOT / "userspace/sui/api/server-shared/src/main/java/rikka/shizuku/server/Service.java").read_text()
        production = "\n".join(method(source, sig) for source, sig in (
            (sui, "public boolean checkCallerPermission("),
            (base, "public final void enforceCallingPermission("),
        ))
        run_java(r'''
public class Check {
    @interface Nullable {}
    static class Binder { static int uid = 10123; static int getCallingUid() { return uid; } static int getCallingPid() { return 1; } }
    static class OsUtils { static int getUid() { return 0; } }
    static class ClientRecord {}
    static class Clients { ClientRecord findClient(int u, int p) { return null; } }
    static class SuiConfig { static class PackageEntry { int flags; } }
    static class Config { SuiConfig.PackageEntry find(int uid) { return null; } }
    static class LunexsuManagerGate { static boolean signed = true; static boolean accepts(int uid) { return uid == 10123 && signed; } }
    static class Logger { void w(String msg) {} }
    static final Logger LOGGER = new Logger();
    final Clients clientManager = new Clients(); final Config configManager = new Config();
    boolean isClientAuthorized(ClientRecord r) { return false; }
    boolean isPermissionAllowedForCurrentServer(int f) { return false; }
    void denied(String function) {
        try { enforceCallingPermission(function); throw new AssertionError(function); } catch (SecurityException expected) {}
    }
''' + production + r'''
    public static void main(String[] args) {
        Check c = new Check();
        c.enforceCallingPermission("getUid"); c.enforceCallingPermission("getVersion");
        for (String f : new String[] {"newProcess", "transactRemote", "createHost", "addUserService", "getSystemProperty"}) c.denied(f);
        LunexsuManagerGate.signed = false; c.denied("getUid"); c.denied("getVersion");
        LunexsuManagerGate.signed = true; Binder.uid = 110123; c.denied("getUid"); c.denied("getVersion");
    }
}
''')

    def test_rish_constructor_start_and_client_failures_release_real_epoch_lease(self):
        rish = (ROOT / "userspace/sui/api/rish/src/main/java/rikka/rish/RishService.java").read_text()
        sui = SERVER.read_text()
        production = method(rish, "private void createHost(") + "\n" + "\n".join(method(sui, sig) for sig in (
            "private static final class CapabilityEpochState", "private CapabilityEpochState capabilityStateForUid(",
            "protected long beginCapabilityCreation(", "protected boolean finishCapabilityCreation(",
            "protected void abortCapabilityCreation(", "private void beginPermissionTransition(",
            "private void finishPermissionTransition(",
        ))
        run_java(r'''
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
public class Check {
    static class Binder { static int getCallingUid() { return 10123; } static int getCallingPid() { return 123; } }
    static class ParcelFileDescriptor {}
    interface IBinder { interface DeathRecipient { void binderDied(); } void linkToDeath(DeathRecipient r, int f); }
    static class Log { static void d(String t, String s) {} }
    static final String TAG = "test"; static final boolean IS_ROOT = true;
    static final ConcurrentHashMap<Integer, Integer> EXIT_CODES = new ConcurrentHashMap<>();
    static int failure, destroyed;
    final ConcurrentHashMap<Integer, CapabilityEpochState> capabilityEpochStates = new ConcurrentHashMap<>();
    static class ClientRecord {}
    static class Clients { ClientRecord findClient(int uid, int pid) { return new ClientRecord(); } }
    final Clients clientManager = new Clients();
    boolean isClientAuthorized(ClientRecord r) { return true; }
    static class RishHost {
        RishHost(String[] a, String[] e, String d, byte t, ParcelFileDescriptor i, ParcelFileDescriptor o, ParcelFileDescriptor x) {
            if (failure == 1) throw new IllegalArgumentException("constructor");
        }
        void start() { if (failure == 2) throw new IllegalArgumentException("start"); }
        void destroy() { destroyed++; }
        int getPid() { return 100; } int getExitCode() { return 0; }
        void setExitCleanup(Runnable r) {}
    }
    IBinder getClientBinder(int uid, int pid) {
        if (failure == 3) throw new IllegalArgumentException("client lookup");
        return failure == 4 ? (r, f) -> { throw new IllegalArgumentException("dead"); } : null;
    }
    RishHost removeHost(int p) { return null; } boolean removeHost(int p, RishHost h) { return true; }
    RishHost putHost(int p, RishHost h) { return null; } void putExitCode(int p, int c) {}
''' + production + r'''
    public static void main(String[] args) throws Exception {
        Check c = new Check();
        for (int mode = 0; mode < 6; mode++) {
            failure = mode; destroyed = 0;
            boolean threw = false;
            try { c.createHost(mode == 0 ? null : new String[] {"sh"}, mode == 5 ? null : new String[0],
                              null, (byte)0, null, null, null); }
            catch (RuntimeException expected) { threw = true; }
            if (!threw || c.capabilityStateForUid(10123).inFlight != 0) throw new AssertionError("lease leak " + mode);
            Thread revoke = new Thread(() -> { c.beginPermissionTransition(10123); c.finishPermissionTransition(10123); });
            revoke.setDaemon(true); revoke.start(); revoke.join(1000);
            if (revoke.isAlive()) throw new AssertionError("revoke hung " + mode);
            if (mode >= 2 && mode <= 4 && destroyed == 0) throw new AssertionError("host leaked");
        }
        failure = 6;
        c.createHost(new String[] {"sh"}, new String[0], null, (byte)0, null, null, null);
        if (c.capabilityStateForUid(10123).inFlight != 0) throw new AssertionError("commit leaked lease");
    }
}
''')

    def test_user_service_cache_remove_and_package_revoke_are_full_uid_scoped(self):
        source = (ROOT / "userspace/sui/api/server-shared/src/main/java/rikka/shizuku/server/UserServiceManager.java").read_text()
        signatures = (
            "private static String packageKey(", "private static String serviceKey(",
            "public PackageInfo ensureCallingPackageForUserService(",
            "public int removeUserService(", "public int addUserService(",
            "private UserServiceRecord getUserServiceRecordLocked(",
            "private UserServiceRecord createUserServiceRecordIfNeededLocked(",
            "private void removeUserServiceLocked(UserServiceRecord record)",
            "private void removeUserServiceLocked(UserServiceRecord record, boolean securityRevoke)",
            "public synchronized void removeUserServicesForPackage(",
            "public synchronized void revokeUserServicesForPackage(",
            "public synchronized boolean hasUserServicesForPackage(",
        )
        production = "\n".join(method(source, sig) for sig in signatures)
        constants = re_constants(source)
        run_java(r'''
import java.util.*;
public class UserServiceManager {
    @interface SuppressLint { String value(); }
    static class Logger { void i(String s, Object... a) {} void v(String s, Object... a) {} }
    static final Logger LOGGER = new Logger();
    final Map<String, UserServiceRecord> userServiceRecords = new HashMap<>();
    final Map<String, List<UserServiceRecord>> packageUserServiceRecords = new HashMap<>();
    final java.util.concurrent.Executor executor = r -> {};
    static class Binder { static int uid; static int getCallingUid() { return uid; } static int getCallingPid() { return 1; } }
    static class UserHandleCompat { static int getAppId(int uid) { return uid % 100000; } static int getUserId(int uid) { return uid / 100000; } }
    static class ComponentName { String getPackageName() { return "com.example"; } String getClassName() { return "Service"; } }
    static class Bundle {
        final Map<String, Object> data = new HashMap<>();
        String getString(String k) { return (String)data.get(k); }
        boolean containsKey(String k) { return data.containsKey(k); }
        boolean getBoolean(String k) { return getBoolean(k, false); }
        boolean getBoolean(String k, boolean d) { return (boolean)data.getOrDefault(k, d); }
        int getInt(String k, int d) { return (int)data.getOrDefault(k, d); }
    }
    static class ApplicationInfo { String sourceDir = "apk"; int uid; }
    static class PackageInfo { String packageName = "com.example"; ApplicationInfo applicationInfo = new ApplicationInfo(); }
    static class IShizukuServiceConnection {}
    static class Callbacks { void register(IShizukuServiceConnection c) {} void unregister(IShizukuServiceConnection c) {} }
    static class LiveBinder { boolean pingBinder() { return true; } }
    static abstract class UserServiceRecord {
        final int ownerUid, versionCode; boolean daemon, starting, capabilityPending = true, destroyed;
        String token = "token"; LiveBinder service = new LiveBinder(); final Callbacks callbacks = new Callbacks();
        UserServiceRecord(int version, boolean d, int uid, int pid, long epoch) { versionCode = version; daemon = d; ownerUid = uid; }
        abstract void removeSelf();
        void destroy() { destroyed = true; } void securityDestroy() { destroyed = true; }
        void setDaemon(boolean d) { daemon = d; } void broadcastBinderReceived() {}
        void setStartingTimeout(long t) { starting = true; }
    }
    static class DateUtils { static final long SECOND_IN_MILLIS = 1000; }
    static <T> T getParcelable(Bundle b, String k, Class<T> c) { return c.cast(new ComponentName()); }
    static class PackageManagerApis {
        static boolean wrongUser;
        static PackageInfo getPackageInfoNoThrow(String p, int flags, int user) {
            PackageInfo info = new PackageInfo();
            info.applicationInfo.uid = wrongUser ? 10123 : Binder.uid; return info;
        }
    }
    long beginUserServiceCapabilityCreation(int u, int p) { return 1; }
    void abortUserServiceCapabilityCreation(UserServiceRecord r) {}
    void onUserServiceRecordCreated(UserServiceRecord r, PackageInfo p) {}
    void onUserServiceRecordRemoved(UserServiceRecord r) {}
    void startUserService(UserServiceRecord r, String k, String t, String p, String c, String n, int uid, boolean b, boolean d) {}
''' + constants + production + r'''
    static void check(boolean v) { if (!v) throw new AssertionError(); }
    public static void main(String[] args) {
        UserServiceManager m = new UserServiceManager(); Bundle opts = new Bundle();
        opts.data.put(USER_SERVICE_ARG_TAG, "same-tag");
        IShizukuServiceConnection c = new IShizukuServiceConnection();
        Binder.uid = 10123; m.addUserService(c, opts, 13, false);
        UserServiceRecord primary = m.userServiceRecords.get(serviceKey(10123, "com.example", "same-tag", "Service"));
        Binder.uid = 1010123; m.addUserService(c, opts, 13, false);
        UserServiceRecord secondary = m.userServiceRecords.get(serviceKey(1010123, "com.example", "same-tag", "Service"));
        check(primary != secondary && m.userServiceRecords.size() == 2);
        opts.data.put(USER_SERVICE_ARG_NO_CREATE, true);
        check(m.addUserService(c, opts, 13, false) == 1);
        check(m.removeUserService(c, opts) == 0);
        check(!primary.destroyed && secondary.destroyed && m.hasUserServicesForPackage("com.example", 10123));
        check(!m.hasUserServicesForPackage("com.example", 1010123));
        opts.data.put(USER_SERVICE_ARG_NO_CREATE, false);
        m.addUserService(c, opts, 13, false);
        secondary = m.userServiceRecords.get(serviceKey(1010123, "com.example", "same-tag", "Service"));
        m.removeUserServicesForPackage("com.example", 10123);
        check(primary.destroyed && !secondary.destroyed);
        m.revokeUserServicesForPackage("com.example", 1010123);
        check(secondary.destroyed && m.userServiceRecords.isEmpty());
        PackageManagerApis.wrongUser = true;
        try { m.addUserService(c, opts, 13, false); throw new AssertionError("accepted wrong PM user"); }
        catch (SecurityException expected) {}
        PackageManagerApis.wrongUser = false;
        // Defend every lookup, including no-create and removal, against a mismatched owner.
        m.userServiceRecords.put(serviceKey(1010123, "com.example", "same-tag", "Service"), primary);
        opts.data.put(USER_SERVICE_ARG_NO_CREATE, true);
        try { m.addUserService(c, opts, 13, false); throw new AssertionError("wrong cached owner"); }
        catch (SecurityException expected) {}
        try { m.removeUserService(c, opts); throw new AssertionError("removed wrong owner"); }
        catch (SecurityException expected) {}
    }
}
''', "UserServiceManager")


def re_constants(source):
    import re
    return "\n".join('static final String ' + name + ' = "' + name + '";'
                     for name in sorted(set(re.findall(r'\bUSER_SERVICE_ARG_\w+', source)))) + "\n"


class NativeDexRegression(unittest.TestCase):
    @unittest.skipUnless(shutil.which("g++"), "needs C++ compiler")
    def test_null_invalid_and_failed_install_do_not_install_hook(self):
        source = (ROOT / "userspace/sui/module/src/main/cpp/core/system_server.cpp").read_text()
        production = method(source, "void main(JNIEnv* env, Dex* dexFile)")
        harness = r'''
#include <cassert>
#define LOGE(...) ((void)0)
#define LOGV(...) ((void)0)
int hooks = 0, installs = 0;
bool installOk = true;
struct JavaVM {};
struct JNIEnv { void GetJavaVM(JavaVM** p) { *p = nullptr; } };
struct Dex { bool ok; bool valid() { return ok; } };
bool installDex(JNIEnv*, Dex*) { installs++; return installOk; }
void ExecTransact() {}
namespace BinderHook { void Install(JavaVM*, JNIEnv*, void (*)()) { hooks++; } }
namespace SystemServer {
''' + production + r'''
}
int main() {
    JNIEnv env; Dex invalid{false}, valid{true};
    SystemServer::main(&env, nullptr); SystemServer::main(&env, &invalid);
    assert(installs == 0 && hooks == 0);
    installOk = false; SystemServer::main(&env, &valid); assert(installs == 1 && hooks == 0);
    installOk = true; SystemServer::main(&env, &valid); assert(installs == 2 && hooks == 1);
}
'''
        with tempfile.TemporaryDirectory(prefix="sui-dex-") as temporary:
            folder = Path(temporary)
            cpp = folder / "check.cpp"
            cpp.write_text(harness)
            binary = folder / "check"
            subprocess.run(["g++", "-std=c++17", "-Wall", "-Wextra", "-Werror",
                            str(cpp), "-o", str(binary)], check=True, timeout=60)
            subprocess.run([str(binary)], check=True, timeout=15)


if __name__ == "__main__":
    unittest.main()
