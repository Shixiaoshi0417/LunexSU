"""Device integration tests. Never flash a kernel or enable adb root.

Requires matching Core+ksud+recognized Manager and its androidTest APK,
and an already-installed disposable debuggable fixture.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import re
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial")
parser.add_argument("--user", type=int, default=0, help="already-created Android user ID")
parser.add_argument("--manager", default="com.shixiaoshi0417.lunexsu")
parser.add_argument("--fixture", required=True)
parser.add_argument("--probe", help="exec_probe path accessible to run-as")
args = parser.parse_args()
if not 0 <= args.user <= 21474:
    parser.error("Android user ID out of Core's signed UID range")
for pkg in (args.manager, args.fixture):
    if not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", pkg):
        parser.error("invalid package")
if args.probe and not re.fullmatch(r"/[A-Za-z0-9_./-]+", args.probe):
    parser.error("invalid probe path")
adb = [args.adb] + (["-s", args.serial] if args.serial else [])


def control(action, mode=1, seconds=300):
    call = subprocess.run(adb + ["shell", "am", "instrument", "-w", "--user", str(args.user),
        "-e", "fixture", args.fixture, "-e", "action", action,
        "-e", "mode", str(mode), "-e", "seconds", str(seconds),
        args.manager + ".test/com.shixiaoshi0417.lunexsu.AuthorizationTestRunner"],
        text=True, capture_output=True, timeout=60)
    output = call.stdout + call.stderr
    if call.returncode or "INSTRUMENTATION_CODE: -1" not in output or "error=" in output:
        raise RuntimeError(output)
    state = re.search(r"uid=(\d+) mode=(\d+) state=(\d+) remaining=(\d+) configured=(true|false)", output)
    if not state:
        raise RuntimeError("No authorization result: " + output)
    if int(state.group(1)) // 100000 != args.user:
        raise RuntimeError("Instrumentation returned a different Android user UID")
    return tuple(state.groups())


def su(_=None):
    call = subprocess.run(adb + ["shell", "run-as", args.fixture, "--user", str(args.user), "/system/bin/su", "-c", "'id -u'"],
        text=True, capture_output=True, timeout=30)
    return call.returncode == 0 and call.stdout.strip() == "0"


original = control("inspect")
if original[4] != "false":
    raise SystemExit("Refusing a fixture with existing authorization. Revoke it explicitly first.")
try:
    assert not su(), "denied fixture unexpectedly has Root"
    control("keep_grant")
    assert not su(), "KEEP created a new authorization"
    control("grant", mode=0)
    assert control("inspect")[1] == "0", "Permanent precondition failed"
    control("reject_identity")
    control("grant", mode=1)
    assert control("inspect")[1:3] == ("1", "1"), "Permanent-to-Once did not save/read back"
    with ThreadPoolExecutor(max_workers=32) as pool:
        successes = sum(pool.map(su, range(32)))
    assert successes == 1, f"Once admitted {successes} successful Root processes"
    assert not su()
    control("edit")
    assert not su(), "metadata edit restored a consumed Once grant"
    control("stale")
    assert not su(), "stale writer restored a consumed Once grant"
    if args.probe:
        control("grant", mode=1)
        attempt = subprocess.run(adb + ["shell", "run-as", args.fixture, "--user", str(args.user), args.probe, "ioctl"],
            text=True, capture_output=True, timeout=30)
        assert attempt.returncode != 0 and "denied:" in attempt.stdout
        assert control("inspect")[2] == "1", "ioctl consumed Once"
        attempt = subprocess.run(adb + ["shell", "run-as", args.fixture, "--user", str(args.user), args.probe, "fault"],
            text=True, capture_output=True, timeout=30)
        assert attempt.returncode != 0 and "unexpected return" not in attempt.stderr
        assert control("inspect")[2] == "1", "failed exec did not refund"
        assert su() and not su()
    control("grant", mode=2, seconds=3)
    assert su() and su(), "Timed did not permit repeated su"
    time.sleep(3.2)
    assert not su(), "Timed allowed an expired request"
    control("edit")
    assert not su(), "KEEP extended deadline"
    control("stale")
    assert not su(), "stale writer restored an expired Timed grant"
    control("grant", mode=2, seconds=300)
    assert su(), "renewal failed"
    control("revoke")
    assert not su(), "revocation failed"
    control("grant", mode=0)
    assert su() and su(), "Permanent regression"
    control("revoke")
    control("keep_grant")
    assert not su(), "KEEP restored a revoked Permanent authorization"
    print("PASS: actual su contention, expiry/renew/revoke/KEEP/stale writes, Permanent" + (", failed exec/ioctl" if args.probe else " (probe tests skipped)"))
finally:
    control("revoke")
