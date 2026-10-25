"""Protect the imported baseline and keep local Sui adaptations explicit."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from sui_source_inputs import source_inputs

SOURCE = ROOT / 'userspace/sui'
BASE = '40c97e24d155f23364d8ebfe4b247034dc6547ef'
CHANGED = {
    '.gitignore',
    'build.gradle.kts',
    'gradle/wrapper/gradle-wrapper.properties', 'module.gradle.kts',
    'module/build.gradle.kts', 'module/src/main/cpp/CMakeLists.txt',
    'module/src/main/cpp/main/sui_main.hpp', 'module/src/main/cpp/util/include/selinux.h',
    'module/src/main/cpp/util/selinux.cpp',
    'module/src/main/cpp/core/system_server.cpp',
    'api/rish/src/main/java/rikka/rish/RishService.java',
    'api/server-shared/src/main/java/rikka/shizuku/server/ClientRecord.java',
    'api/server-shared/src/main/java/rikka/shizuku/server/UserServiceManager.java',
    'module/src/main/java/rikka/sui/server/FinasuManagerGate.java',
    'module/src/main/java/rikka/sui/server/SuiConfigManager.java',
    'module/src/main/java/rikka/sui/server/SuiConfig.java',
    'module/src/main/java/rikka/sui/server/SuiDatabase.java',
    'module/src/main/java/rikka/sui/server/ServerConstants.java',
    'module/src/main/java/rikka/sui/server/SuiService.java',
    'module/src/main/java/rikka/sui/systemserver/BridgeService.java',
    'module/src/main/java/rikka/sui/systemserver/SystemProcess.java', 'package.py',
    # The SystemUI permission dialog binds its reply to the server-reported full-UID identity.
    'module/src/main/java/rikka/sui/manager/ManagerProcess.java',
    'ui/src/main/java/rikka/sui/permission/ConfirmationDialog.java',
    'ui/src/main/java/rikka/sui/util/BridgeServiceClient.java',
    'ui/src/main/res/layout/confirmation_dialog.xml',
    'ui/src/main/res/values/strings.xml', 'ui/src/main/res/values-ja/strings.xml',
    'ui/src/main/res/values-zh-rCN/strings.xml', 'ui/src/main/res/values-zh-rTW/strings.xml',
}
ADDED = {'SOURCE.lock.json', 'LUNEXSU.md',
          'module/src/main/java/rikka/sui/server/LunexsuManagerGate.java',
          'module/src/main/java/rikka/sui/server/ManagerTrust.java',
          'module/src/main/java/rikka/sui/server/UidIdentity.java',
          'ui/src/main/java/rikka/sui/permission/RequestIdentity.java',
          # The shell runtime directory is UID 2000's: root reaches it only through descriptors.
          'module/src/main/cpp/util/include/shell_runtime.h', 'module/src/main/cpp/util/shell_runtime.cpp',
          'module/src/main/java/rikka/sui/server/ShellRuntimeFiles.java'}


def baseline_matches(name, raw, digest):
    if raw is None:
        return False
    if hashlib.sha256(raw).hexdigest() == digest:
        return True
    # The pinned .gitattributes checks out Windows launchers as CRLF. The
    # provenance lock records the canonical Git blob, not archive/checkout EOLs.
    return name.endswith('.bat') and hashlib.sha256(raw.replace(b'\r\n', b'\n')).hexdigest() == digest


class SourceProvenanceTests(unittest.TestCase):
    def test_fixed_baseline_and_reviewed_adaptations(self):
        lock = json.loads((SOURCE / 'SOURCE.lock.json').read_text(encoding='utf-8'))
        self.assertEqual(lock['repository'], 'LyraVoid/FinaSU')
        self.assertEqual(lock['commit'], BASE)
        self.assertEqual(len(lock['files']), 365)
        inputs = source_inputs(SOURCE)
        changed = set()
        for name, entry in lock['files'].items():
            self.assertEqual(PurePosixPath(name).as_posix(), name)
            self.assertNotIn('..', PurePosixPath(name).parts)
            self.assertIn(entry['mode'], ('100644', '100755'))
            self.assertRegex(entry['git_blob'], r'^[0-9a-f]{40}$')
            self.assertRegex(entry['sha256'], r'^[0-9a-f]{64}$')
            if inputs.get(name) != entry['sha256']:
                path = SOURCE / name
                if not baseline_matches(name, path.read_bytes() if path.is_file() else None, entry['sha256']):
                    changed.add(name)
        self.assertEqual(changed, CHANGED)
        self.assertEqual(set(inputs) - set(lock['files']), ADDED)

    def test_upstream_licenses_and_api_pin_remain_unmodified(self):
        lock = json.loads((SOURCE / 'SOURCE.lock.json').read_text(encoding='utf-8'))
        for name in ('LICENSE', 'api/LICENSE', 'UPSTREAM.md'):
            actual = hashlib.sha256((SOURCE / name).read_bytes()).hexdigest()
            self.assertEqual(actual, lock['files'][name]['sha256'])
        text = (SOURCE / 'UPSTREAM.md').read_text(encoding='utf-8')
        self.assertIn('e9674238a0dcfe13d9ea231cbdf866144e660db8', text)
        self.assertIn('13aeedbc9d83e65b3a4c74b736ca54c768dcb819', text)

    def test_only_declared_batch_checkout_line_endings_are_equivalent(self):
        raw = b'@echo off\nexit /b 0\n'
        digest = hashlib.sha256(raw).hexdigest()
        self.assertTrue(baseline_matches('api/gradlew.bat', raw.replace(b'\n', b'\r\n'), digest))
        self.assertFalse(baseline_matches('api/gradlew.bat', raw + b'rem changed\n', digest))
        self.assertFalse(baseline_matches('code.java', raw.replace(b'\n', b'\r\n'), digest))
        self.assertFalse(baseline_matches('api/gradlew.bat', None, digest))


if __name__ == '__main__':
    unittest.main()
