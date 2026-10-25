"""Import the unmodified, fixed FinaSU Sui subtree into an empty destination.

This source import does not run Gradle, installers, daemons or device commands.
Every imported file is checked against the selected commit's Git blob identity.
Existing files are never replaced. Subsequent local patches are maintained in
this repository, not applied by this importer.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import tarfile
import urllib.request

COMMIT = "40c97e24d155f23364d8ebfe4b247034dc6547ef"
REPOSITORY = "LyraVoid/FinaSU"
PREFIX = "userspace/sui/"


def download(url, path):
    if path.exists():
        return
    request = urllib.request.Request(url, headers={"User-Agent": "LunexSU-source-import"})
    with urllib.request.urlopen(request, timeout=90) as source:
        data = source.read()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".partial")
    temporary.write_bytes(data)
    temporary.rename(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    destination = root / "userspace/sui"
    if destination.exists():
        parser.error("userspace/sui already exists; refusing to overwrite local patches")
    archive = args.cache / f"FinaSU-{COMMIT}.tar.gz"
    commit_metadata = args.cache / f"FinaSU-{COMMIT}-commit.json"
    download(f"https://codeload.github.com/{REPOSITORY}/tar.gz/{COMMIT}", archive)
    download(f"https://api.github.com/repos/{REPOSITORY}/git/commits/{COMMIT}", commit_metadata)
    commit = json.loads(commit_metadata.read_text(encoding="utf-8"))
    metadata = args.cache / f"FinaSU-tree-{commit['tree']['sha']}.json"
    download(f"https://api.github.com/repos/{REPOSITORY}/git/trees/{commit['tree']['sha']}?recursive=1", metadata)
    tree = json.loads(metadata.read_text(encoding="utf-8"))
    if commit["sha"] != COMMIT or tree.get("truncated") or tree["sha"] != commit["tree"]["sha"]:
        raise ValueError("Incomplete or unexpected source tree")
    expected = {entry["path"][len(PREFIX):]: entry for entry in tree["tree"]
                if entry["path"].startswith(PREFIX) and entry["type"] == "blob"}
    checked = {}
    with tarfile.open(archive, "r:gz") as source:
        for member in source:
            parts = PurePosixPath(member.name).parts
            relative = "/".join(parts[1:])
            if not relative.startswith(PREFIX) or not member.isfile():
                continue
            name = relative[len(PREFIX):]
            if name not in expected or ".." in PurePosixPath(name).parts:
                raise ValueError(f"Unexpected source member: {name}")
            entry = expected[name]
            if entry["mode"] not in ("100644", "100755") or name in checked:
                raise ValueError(f"Unsupported or duplicate source member: {name}")
            data = source.extractfile(member).read()
            blob = b"blob " + str(len(data)).encode("ascii") + b"\0" + data
            # GitHub archives honor eol=crlf attributes; import canonical Git
            # bytes only when normalization reproduces the pinned blob exactly.
            if hashlib.sha1(blob).hexdigest() != entry["sha"]:
                data = data.replace(b"\r\n", b"\n")
                blob = b"blob " + str(len(data)).encode("ascii") + b"\0" + data
            if hashlib.sha1(blob).hexdigest() != entry["sha"]:
                raise ValueError(f"Source identity mismatch: {name}")
            checked[name] = (data, entry["mode"])
    if checked.keys() != expected.keys() or not checked:
        raise ValueError("Source archive does not contain the complete pinned subtree")
    records = {}
    for name, (data, mode) in checked.items():
        path = destination / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("xb") as output:
            output.write(data)
        path.chmod(0o755 if mode == "100755" else 0o644)
        records[name] = {"git_blob": expected[name]["sha"], "mode": mode,
                         "sha256": hashlib.sha256(data).hexdigest()}
    manifest = {"repository": REPOSITORY, "commit": COMMIT,
                "archive_sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                "files": records}
    (destination / "SOURCE.lock.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Imported {len(records)} verified source files; no build or device actions run.")


if __name__ == "__main__":
    main()
