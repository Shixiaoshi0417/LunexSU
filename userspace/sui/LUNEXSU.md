# LunexSU adaptations

This source is imported from the exact FinaSU revision recorded in
`SOURCE.lock.json`. That file records the **unmodified upstream baseline**, not
the hashes of subsequent LunexSU adaptations. `UPSTREAM.md` describes FinaSU's
underlying Sui and Shizuku API revisions. Their notices remain intact.

Local changes use the `lunexsu-sui` module identity, retain FinaSU attribution,
pin the Gradle distribution checksum, and validate the build-only per-ABI payload
before publication. This payload packager is not an on-device installer: its
output must be embedded in ksud and deployed through the runtime's durable
transaction protocol. It must never be used to replace live Android directories.

Sui is optional and starts disabled. Its application permissions remain separate
from kernel root grants. No Root Demo must contain neither this payload nor a
real privileged Sui bridge. Integration and runtime validation are in progress;
importing this source does not establish Android/ROM compatibility.

The managed permission callback belongs only to the Core-certificate-bound
LunexSU Manager, not a later legacy SystemUI attachment. Callback death is
identity checked. This protocol supports one-time Root, but not one-time Shell:
the UI does not offer the latter and the server explicitly denies that invalid
combination instead of treating it as Root. The original SystemUI/Settings
management APIs are otherwise preserved; this is not an ACL merger or a claim
that all administrative entry points were removed.
