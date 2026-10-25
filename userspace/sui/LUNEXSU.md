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
Shell delegation reserves each `(uid, pid, requestCode)` callback atomically.
While that callback is pending, a duplicate is denied through its own new Binder
before Manager admission; it cannot replace or unlink the original callback.
This protocol supports one-time Root, but not one-time Shell:
the UI does not offer the latter and the server explicitly denies that invalid
combination instead of treating it as Root. The original SystemUI/Settings
management APIs are otherwise preserved; this is not an ACL merger or a claim
that all administrative entry points were removed.

Only the certificate-verified Manager receives the pre-attach `getUid` and
`getVersion` handshake exemption. It does not receive normal client execution
permission. Manager attachment checks both its full calling UID and package.

Persistent Root/Shell ACL rows bind the full Android UID to every shared-UID
package, first-install timestamp and current signing-certificate digest. Every
privileged lookup revalidates that binding and fails closed on missing or changed
identity. Same-signer in-place updates retain an ACL; reinstallations, signer or
shared-UID membership changes require fresh consent. Legacy unbound grants become
explicit denial tombstones, and legacy default-allow is cleared during migration.
The shell snapshot carries the same binding. Root periodically revokes stale
materialized capabilities and retries shell revocation synchronization if needed.

Permission requests additionally bind last-update timestamps and the requesting
client instance. The Manager obtains complete identity snapshots through a
root-server management RPC that queries PackageManager for the requested Android
user, rather than substituting the Manager user's package installation. Its grant
RPC includes the displayed fingerprint. UserService cache and package-cleanup
keys include the full owner UID, isolating identical services across users.

The shell runtime directory under `/data/local/tmp` and that directory itself are
owned by the shell user (UID 2000), who can swap any entry for a link between two
root operations. The root starter therefore opens the directory without following
links and recreates `sui.dex`, `librish.so` and metadata as fresh exclusive files
that are labeled, chowned and chmodded through their descriptors before being
renamed into place; legacy migration and cleanup likewise never follow a link. The
root server reads and replaces `sui_uids.txt` through `/proc/self/fd` of that
directory, refusing links, FIFOs and oversized files. Only an existing link is
replaced; nothing outside the directory is opened for writing.
