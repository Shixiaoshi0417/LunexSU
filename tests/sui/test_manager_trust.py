"""Execute the real pure Java trust predicate, without Android/root or API stubs."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "userspace/sui/module/src/main/java/rikka/sui/server/ManagerTrust.java"
JAVA_HOME = Path(os.environ.get("JAVA_HOME", "D:/Android Studio/jbr"))
JAVA = shutil.which("java") or str(JAVA_HOME / "bin/java.exe")
JAVAC = shutil.which("javac") or str(JAVA_HOME / "bin/javac.exe")
CERTIFICATE = bytes(range(32))
HASH = hashlib.sha256(CERTIFICATE).hexdigest()


class ManagerTrustTests(unittest.TestCase):
    def run_java(self, body, package="com.shixiaoshi0417.lunexsu", size=32, digest=HASH):
        with tempfile.TemporaryDirectory(prefix="lunex-sui-trust-") as temporary:
            root = Path(temporary)
            (root / "rikka/sui/server").mkdir(parents=True)
            shutil.copyfile(SOURCE, root / "rikka/sui/server/ManagerTrust.java")
            config = root / "rikka/sui/BuildConfig.java"
            config.write_text('package rikka.sui; public final class BuildConfig {\n'
                              f'public static final String LUNEXSU_MANAGER_PACKAGE="{package}";\n'
                              f'public static final String LUNEXSU_MANAGER_CERT_SHA256="{digest}";\n'
                              f'public static final int LUNEXSU_MANAGER_CERT_SIZE={size};\n}}\n', encoding="utf-8")
            main = root / "Check.java"
            main.write_text('import rikka.sui.server.ManagerTrust; public class Check {\n'
                            'static void check(boolean value) { if (!value) throw new AssertionError(); }\n'
                            'public static void main(String[] args) {\n'
                            'String name="com.shixiaoshi0417.lunexsu";\n'
                            f'String hash="{digest}"; byte[] cert=new byte[32];\n'
                            'for(int i=0;i<cert.length;i++) cert[i]=(byte)i;\n'
                            + body + '\n}}\n', encoding="utf-8")
            sources = [str(path) for path in root.rglob("*.java")]
            subprocess.run([JAVAC, "-encoding", "UTF-8", "-Xlint:all", "-Werror", "-d", str(root / "classes"), *sources], check=True)
            subprocess.run([JAVA, "-cp", str(root / "classes"), "Check"], check=True)

    def test_exact_single_signer_and_uid_required(self):
        self.run_java('''
check(ManagerTrust.accepts(name,10042,hash,10042,new byte[][]{cert}));
check(ManagerTrust.accepts(name,10000,hash.toUpperCase(),10000,new byte[][]{cert}));
check(ManagerTrust.accepts(name,19999,hash,19999,new byte[][]{cert}));
for(int uid:new int[]{-1,0,1000,2000,9999,20000,1010042})
  check(!ManagerTrust.accepts(name,uid,hash,uid,new byte[][]{cert}));
check(!ManagerTrust.accepts(name,10042,hash,10043,new byte[][]{cert}));
for(String other:new String[]{"", "other", name+".pr", name+".demo", "me.yuki.fina"})
  check(!ManagerTrust.accepts(other,10042,hash,10042,new byte[][]{cert}));
check(!ManagerTrust.accepts(name,10042,"00".repeat(32),10042,new byte[][]{cert}));
check(!ManagerTrust.accepts(name,10042,hash,10042,null));
check(!ManagerTrust.accepts(name,10042,hash,10042,new byte[0][]));
check(!ManagerTrust.accepts(name,10042,hash,10042,new byte[][]{null}));
check(!ManagerTrust.accepts(name,10042,hash,10042,new byte[][]{cert,cert}));
check(!ManagerTrust.accepts(name,10042,hash,10042,new byte[][]{new byte[31]}));
cert[0]=99;
check(!ManagerTrust.accepts(name,10042,hash,10042,new byte[][]{cert}));
''')

    def test_pr_compile_only_identity_cannot_grant_management(self):
        self.run_java('''
check(!ManagerTrust.matchesRecord(name+".pr",10042,hash));
check(!ManagerTrust.accepts(name+".pr",10042,hash,10042,new byte[][]{new byte[]{0}}));
''', package="com.shixiaoshi0417.lunexsu.pr", size=1, digest="00" * 32)

    def test_demo_cannot_become_trust_anchor(self):
        self.run_java('check(!ManagerTrust.accepts(name+".demo",10042,hash,10042,new byte[][]{cert}));',
                      package="com.shixiaoshi0417.lunexsu.demo")

    def test_bridge_uses_the_bound_predicate_and_no_old_manager_gate(self):
        server = SOURCE.parent
        self.assertFalse((server / "FinasuManagerGate.java").exists())
        gate = (server / "LunexsuManagerGate.java").read_text(encoding="utf-8")
        self.assertIn("ManagerTrust.matchesRecord(name, uid, certSha256)", gate)
        self.assertIn("ManagerTrust.accepts(", gate)
        self.assertIn('"/data/adb/ksu/lunexsu-sui/manager.json"', gate)
        for path in (ROOT / "userspace/sui/module/src/main/java").rglob("*.java"):
            self.assertNotIn("FinasuManagerGate", path.read_text(encoding="utf-8"), str(path))

    def test_daemon_and_payload_workflows_share_public_core_binding(self):
        workflow = (ROOT / ".github/workflows/build-manager.yml").read_text(encoding="utf-8")
        for job in ("build-sui", "build-ksud", "build-ksud-extra"):
            block = workflow.split(f"  {job}:", 1)[1].split("\n  build-", 1)[0]
            self.assertIn("needs.generate-key.outputs.expected_size", block)
            self.assertIn("needs.generate-key.outputs.expected_hash", block)
        for name in ("ksud.yml", "ksud-extra.yml"):
            source = (ROOT / ".github/workflows" / name).read_text(encoding="utf-8")
            self.assertIn("KSU_EXPECTED_SIZE: ${{ inputs.expected_size }}", source)
            self.assertIn("KSU_EXPECTED_HASH: ${{ inputs.expected_hash }}", source)


if __name__ == "__main__":
    unittest.main()
