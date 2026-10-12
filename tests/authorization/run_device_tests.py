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
    # adb joins shell arguments with spaces and the device shell re-parses them, so the
    # quotes keep "id -u" one argument; unquoted, su would run `sh -c id -u` (plain `id`).
    call = subprocess.run(adb + ["shell", "run-as", args.fixture, "--user", str(args.user), "/system/bin/su", "-c", "'id -u'"],
        text=True, capture_output=True, timeout=30)
    return call.returncode == 0 and call.stdout.strip() == "0"


def su_count(count):
    \"\"\"One App-run process that issues `count` sequential su commands.
    Once is run-scoped, so they must all succeed inside a single run; leaving it ends the grant.\"\"\"
    command = "; ".join(["/system/bin/su -c 'id -u'"] * count)
    call = subprocess.run(adb + ["shell", "run-as", args.fixture, "--user", str(args.user), "sh", "-c", "'%s'" % command],
        text=True, capture_output=True, timeout=120)
    return call.returncode == 0 and call.stdout.split() == ["0"] * count


def live_process(seconds):
    \"\"\"Keep one ordinary App-run process alive so the run (and its grant) stays active.\"\"\"
    return subprocess.Popen(adb + ["shell", "run-as", args.fixture, "--user", str(args.user), "sleep", str(seconds)],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


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
    # Run-scoped: repeated and concurrent su succeed while the App has live processes.
    assert su_count(4), "Once did not sustain repeated su inside one App run"
    with ThreadPoolExecutor(max_workers=16) as pool:
        successes = sum(pool.map(su, range(16)))
    assert successes == 16, f"Once admitted {successes} of 16 concurrent Root processes"
    assert not su(), "Once survived the end of the App run"
    control("grant", mode=1)
    alive = live_process(20)
    try:
        assert su(), "pregrant was not bound to the running App"
        assert su(), "second su inside the same App run was denied"
    finally:
        alive.terminate()
        alive.wait(timeout=30)
    assert not su(), "Once survived after the last App process exited"
    control("edit")
    assert not su(), "metadata edit restored an ended Once grant"
    control("stale")
    assert not su(), "stale writer restored an ended Once grant"
    if args.probe:
        control("grant", mode=1)
        attempt = subprocess.run(adb + ["shell", "run-as", args.fixture, "--user", str(args.user), args.probe, "ioctl"],
            text=True, capture_output=True, timeout=30)
        assert attempt.returncode != 0 and "denied:" in attempt.stdout
        assert control("inspect")[2] == "1", "ioctl consumed the Once grant"
        attempt = subprocess.run(adb + ["shell", "run-as", args.fixture, "--user", str(args.user), args.probe, "fault"],
            text=True, capture_output=True, timeout=30)
        assert attempt.returncode != 0 and "unexpected return" not in attempt.stderr
        assert control("inspect")[2] == "1", "failed exec consumed the Once grant"
        assert su_count(2)
    control("grant", mode=1)
    suspended = live_process(30)
    try:
        assert su(), "Once not usable while the App runs"
        suspended.kill()
        suspended.wait(timeout=30)
        assert not su(), "Once survived a killed App run"
    finally:
        if suspended.poll() is None:
            suspended.kill()
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
    print("PASS: run-scoped repeated/concurrent su, App-run end, expiry/renew/revoke/KEEP/stale writes, Permanent"
          + (", failed exec/ioctl" if args.probe else " (probe tests skipped)"))
finally:
    control("revoke")
