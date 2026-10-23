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
LunexSU Manager, not a later legacy SystemUI attachment. Pending confirmations
are owned by the callback Binder that received them. Its death atomically claims
and denies its remaining requests, even if a replacement has already attached;
client notifications happen outside the state lock and do not change saved ACLs
or other grants. A reply or callback failure can consume each request only once.
Local reconnects with a still-live callback retain their requests as before.
This protocol supports one-time Root, but not one-time Shell:
the UI does not offer the latter and the server explicitly denies that invalid
combination instead of treating it as Root. The original SystemUI/Settings
management APIs are otherwise preserved; this is not an ACL merger or a claim
that all administrative entry points were removed.

The shell runtime directory under `/data/local/tmp` and that directory itself are
owned by the shell user (UID 2000), who can swap any entry for a link between two
root operations. The root starter therefore opens the directory without following
links and recreates `sui.dex`, `librish.so` and metadata as fresh exclusive files
that are labeled, chowned and chmodded through their descriptors before being
renamed into place; legacy migration and cleanup likewise never follow a link. The
root server reads and replaces `sui_uids.txt` through `/proc/self/fd` of that
directory, refusing links, FIFOs and oversized files. Only an existing link is
replaced; nothing outside the directory is opened for writing.
