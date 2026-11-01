"""Execute production UID identity and ACL methods with controlled PackageManager/DB stand-ins.

SQLite migration SQL is executed by real host SQLite. No Android Binder or device is modeled.
"""
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
import unittest

from test_permission_callback import JAVA, JAVAC, ROOT, method

SERVER = ROOT / "userspace/sui/module/src/main/java/rikka/sui/server"

STUBS = {
    "android/os/Binder.java": """
package android.os;
public class Binder {
    public static int clears, restores;
    public static long clearCallingIdentity() { clears++; return 1; }
    public static void restoreCallingIdentity(long value) { restores++; }
}
""",
    "android/content/pm/PackageInfo.java": """
package android.content.pm;
public class PackageInfo {
    public String packageName;
    public ApplicationInfo applicationInfo = new ApplicationInfo();
    public long firstInstallTime = 10, lastUpdateTime = 20;
    public SigningInfo signingInfo = new SigningInfo();
}
""",
    "android/content/pm/ApplicationInfo.java": """
package android.content.pm;
public class ApplicationInfo { public int uid; }
""",
    "android/content/pm/Signature.java": """
package android.content.pm;
public class Signature {
    final byte[] bytes;
    public Signature(byte[] b) { bytes = b; }
    public byte[] toByteArray() { return bytes; }
}
""",
    "android/content/pm/SigningInfo.java": """
package android.content.pm;
public class SigningInfo {
    public Signature[] signers = {new Signature(new byte[] {1, 2, 3})};
    public Signature[] getApkContentsSigners() { return signers; }
}
""",
    "android/text/TextUtils.java": """
package android.text;
public class TextUtils {
    public static String join(String d, Iterable<String> values) { return String.join(d, values); }
}
""",
    "rikka/hidden/compat/PackageManagerApis.java": """
package rikka.hidden.compat;
import android.content.pm.PackageInfo;
import java.util.*;
public class PackageManagerApis {
    public static final Map<String, PackageInfo> installed = new HashMap<>();
    public static List<String> names = List.of("com.example.a", "com.example.b");
    public static Runnable onLookup = () -> {};
    public static boolean changeNames;
    public static int user;
    public static List<String> getPackagesForUidNoThrow(int uid) { return names; }
    public static PackageInfo getPackageInfoNoThrow(String name, int flags, int userId) {
        user = userId; onLookup.run();
        if (changeNames) names = List.of("com.example.a");
        return installed.get(userId + ":" + name);
    }
}
""",
    "org/json/JSONException.java": """
package org.json;
public class JSONException extends Exception { private static final long serialVersionUID = 1; }
""",
    "org/json/JSONObject.java": r'''
package org.json;
public class JSONObject {
    final java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
    public JSONObject put(String key, Object value) throws JSONException { map.put(key, value); return this; }
    static String value(Object v) {
        return v instanceof String ? "\"" + ((String)v).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"" : v.toString();
    }
    public String toString() {
        java.util.List<String> entries = new java.util.ArrayList<>();
        map.forEach((k,v) -> entries.add(value(k) + ":" + value(v)));
        return "{" + String.join(",", entries) + "}";
    }
}
''',
    "org/json/JSONArray.java": """
package org.json;
public class JSONArray {
    final java.util.List<Object> items = new java.util.ArrayList<>();
    public JSONArray() {}
    public JSONArray(java.util.Collection<?> values) { items.addAll(values); }
    public JSONArray put(Object v) { items.add(v); return this; }
    public String toString() {
        java.util.List<String> values = new java.util.ArrayList<>();
        for (Object v : items) values.add(JSONObject.value(v));
        return "[" + String.join(",", values) + "]";
    }
}
""",
    "androidx/annotation/NonNull.java": "package androidx.annotation; public @interface NonNull {}",
    "rikka/shizuku/server/ConfigPackageEntry.java": "package rikka.shizuku.server; public class ConfigPackageEntry {}",
}


@unittest.skipUnless(JAVA and JAVAC, "needs JDK")
class IdentityAclTests(unittest.TestCase):
    def test_install_members_signers_multiuser_and_concurrent_revoke(self):
        source = (SERVER / "SuiConfigManager.java").read_text()
        production = "\n".join(method(source, sig) for sig in (
            "private SuiConfig.PackageEntry findLocked(",
            "@Nullable public SuiConfig.PackageEntry find(int uid)",
            "public void update(int uid, int mask, int values)",
            "public void update(int uid, int mask, int values, String expectedConsent)",
            "static boolean needsIdentity(", "public List<SuiConfig.PackageEntry> staleGrants(",
            "public synchronized boolean denyStaleGrant(",
        ))
        harness = r'''
package rikka.sui.server;
import android.content.pm.*;
import rikka.hidden.compat.PackageManagerApis;
import java.util.*;
public class Check {
    @interface Nullable {}
    static final int DEFAULT_UID = -1;
    static final class Logger { void i(String s) {} void d(String s, Object... args) {} }
    static final Logger LOGGER = new Logger();
    final SuiConfig config = new SuiConfig();
    final Map<Integer, SuiConfig.PackageEntry> packageIndex = new HashMap<>();
    void invalidateUidCacheLocked() {}
    void scheduleSyncUidsToShellFile() {}
    static class SuiService { static boolean isShellMode() { return false; } }
    static class SuiDatabase {
        static final Map<Integer, String> bindings = new HashMap<>();
        static boolean fail;
        static void updateUid(int uid, int flags, String identity) {
            if (fail) throw new IllegalStateException("disk failure");
            bindings.put(uid, flags + ":" + identity);
        }
        static void removeUid(int uid) { bindings.remove(uid); }
    }
    static void check(boolean v) { if (!v) throw new AssertionError(); }
    static void denied(Runnable r) {
        try { r.run(); throw new AssertionError("accepted"); } catch (SecurityException expected) {}
    }
    static PackageInfo install(int uid, String name) {
        PackageInfo p = new PackageInfo(); p.packageName = name; p.applicationInfo.uid = uid;
        PackageManagerApis.installed.put(uid / 100000 + ":" + name, p); return p;
    }
''' + production + r'''
    public static void main(String[] args) throws Exception {
        int uid = 1010123;
        PackageInfo a = install(uid, "com.example.a"), b = install(uid, "com.example.b");
        PackageInfo primary = install(10123, "com.example.a"); primary.firstInstallTime = 99;
        UidIdentity identity = UidIdentity.capture(uid);
        check(PackageManagerApis.user == 10);
        String binding = identity.binding(), consent = identity.consentFingerprint();
        System.out.println(identity.toJson());
        Check config = new Check();
        config.update(uid, 30, 2, consent);
        check(config.find(uid).isAllowed());
        check(SuiDatabase.bindings.get(uid).equals("2:" + binding));
        a.lastUpdateTime++;
        check(UidIdentity.matches(uid, binding)); // same signer in-place update keeps the ACL
        check(!consent.equals(UidIdentity.capture(uid).consentFingerprint()));
        denied(() -> config.update(uid, 30, 16, consent));
        a.firstInstallTime++; // offline uninstall/reinstall, same package and signing key
        check(config.find(uid).isDenied());
        config.update(-1, 30, 2); // stale explicit grants never fall through to default allow
        check(config.find(uid).isDenied());
        check(config.staleGrants().size() == 1);
        SuiConfig.PackageEntry stale = config.staleGrants().get(0);
        config.update(uid, 30, 2, UidIdentity.capture(uid).consentFingerprint());
        check(!config.denyStaleGrant(stale)); // an old reconciliation cannot revoke a newer grant
        b.signingInfo.signers = new Signature[] {new Signature(new byte[] {9})};
        check(config.find(uid).isDenied());
        b.signingInfo.signers = new Signature[] {new Signature(new byte[] {1,2,3})};
        PackageManagerApis.names = List.of("com.example.a");
        check(config.find(uid).isDenied());
        PackageManagerApis.names = List.of("com.example.a", "com.example.c");
        install(uid, "com.example.c");
        check(config.find(uid).isDenied());
        PackageManagerApis.names = List.of("com.example.a", "com.example.b");
        b.applicationInfo.uid = 10123;
        check(config.find(uid).isDenied()); // never substitute user zero for user ten
        b.applicationInfo.uid = uid;
        b.signingInfo = null;
        check(config.find(uid).isDenied());
        b.signingInfo = new SigningInfo();
        PackageManagerApis.changeNames = true;
        denied(() -> UidIdentity.capture(uid));
        PackageManagerApis.changeNames = false;
        PackageManagerApis.names = List.of("com.example.a", "com.example.b");
        // A denial completed while PM was being queried must beat this stale grant operation.
        PackageManagerApis.onLookup = () -> {
            PackageManagerApis.onLookup = () -> {};
            config.update(uid, 30, 4);
        };
        denied(() -> config.update(uid, 30, 2));
        check(config.find(uid).isDenied() && SuiDatabase.bindings.get(uid).startsWith("4:"));
        SuiDatabase.fail = true;
        try { config.update(uid, 30, 2); throw new AssertionError(); }
        catch (IllegalStateException expected) {}
        check(config.find(uid).isDenied());
        check(android.os.Binder.clears == android.os.Binder.restores);
    }
}
'''
        with tempfile.TemporaryDirectory(prefix="sui-acl-") as temporary:
            folder = Path(temporary)
            files = []
            for name, text in {**STUBS, "rikka/sui/server/Check.java": harness}.items():
                file = folder / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(text)
                files.append(str(file))
            files.extend(str(SERVER / f) for f in ("UidIdentity.java", "SuiConfig.java"))
            subprocess.run([JAVAC, "-Xlint:all", "-Werror", "-d", str(folder), *files], check=True, timeout=60)
            result = subprocess.run([JAVA, "-cp", str(folder), "rikka.sui.server.Check"],
                                    check=True, capture_output=True, text=True, timeout=30)
            wire = json.loads(result.stdout)
            self.assertEqual((1, 1010123), (wire["schema"], wire["uid"]))
            self.assertEqual(["com.example.a", "com.example.b"], [p["package"] for p in wire["packages"]])
            self.assertTrue(all(p["uid"] == 1010123 and len(p["signers"][0]) == 64 for p in wire["packages"]))

    def test_legacy_sql_migration_denies_unbound_root_and_shell(self):
        source = (SERVER / "SuiDatabase.java").read_text()
        def sql(prefix):
            text = re.search(r'database\.execSQL\(("' + prefix + r'.*?")\);', source, re.S).group(1)
            return "".join(json.loads(s) for s in re.findall(r'"(?:[^"\\]|\\.)*"', text))
        db = sqlite3.connect(":memory:")
        self.addCleanup(db.close)
        db.execute("CREATE TABLE uid_configs(uid INTEGER PRIMARY KEY, flags INTEGER)")
        db.executemany("INSERT INTO uid_configs VALUES (?, ?)", [(-1, 2), (10123, 2), (10124, 16), (10125, 8), (10126, 4)])
        db.execute(sql("ALTER TABLE"))
        db.execute(sql("DELETE FROM"))
        db.execute(sql("UPDATE uid_configs"))
        self.assertEqual([(10123, 4), (10124, 4), (10125, 8), (10126, 4)],
                         db.execute("SELECT uid, flags FROM uid_configs ORDER BY uid").fetchall())
        db.execute("INSERT INTO uid_configs VALUES (10127, 2, 'bound')")
        db.execute(sql("UPDATE uid_configs"))
        self.assertEqual((2, "bound"), db.execute("SELECT flags, identity FROM uid_configs WHERE uid=10127").fetchone())


if __name__ == "__main__":
    unittest.main()
