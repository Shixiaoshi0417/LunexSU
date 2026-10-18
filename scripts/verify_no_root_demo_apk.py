"""Fail CI if an isolated UI demo accidentally packages a production identity/payload."""
import os
from pathlib import Path
import subprocess
import sys
import zipfile


def verify(directory):
    apks = list(Path(directory).glob('*.apk'))
    assert len(apks) == 1, 'Expected exactly one demo APK'
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    tools = sdk / 'build-tools' / '37.0.0'
    executable = '.exe' if os.name == 'nt' else ''
    aapt = str(tools / ('aapt2' + executable))
    badging = subprocess.check_output([aapt, 'dump', 'badging', str(apks[0])], text=True, encoding='utf-8')
    assert "package: name='com.shixiaoshi0417.lunexsu.demo'" in badging
    assert '-no-root-demo' in badging
    manifest = subprocess.check_output([aapt, 'dump', 'xmltree', str(apks[0]), '--file', 'AndroidManifest.xml'], text=True, encoding='utf-8')
    for forbidden in ('zygotePreloadName', 'AppZygotePreload', 'BootCompletedReceiver', 'MagicaService', 'RECEIVE_BOOT_COMPLETED'):
        assert forbidden not in manifest, forbidden
    with zipfile.ZipFile(apks[0]) as apk:
        for name in apk.namelist():
            assert not name.endswith(('/libksud.so', '/liblunexsu.so')), name
            assert not name.startswith('assets/zygisk/'), name
    print('Verified independent demo identity and absence of root payloads/boot hooks')


if __name__ == '__main__':
    verify(sys.argv[1])
