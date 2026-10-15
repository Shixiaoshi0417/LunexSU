# Optional Manager superkey

## Installation and authentication

- Boot/init_boot patching, download-and-patch, and direct installation offer an optional superkey. It is off by default; an unconfigured Core retains the existing package/certificate Manager verification.
- Enabling it requires matching entries of 12-128 printable ASCII characters without spaces. Use a unique, randomly generated key and keep it somewhere safe.
- The installed Core requires both its existing Manager package/certificate identity check and a successful superkey authentication. A key never appoints an arbitrary app as Manager.
- Whenever `Natives.isManager` is false, Home offers the input entry, including when no Core is installed. All failed attempts use the same message: `可能未安装或密码错误` (Core may not be installed, or the key is incorrect).
- A successful authentication enables the current full Manager UID until a reboot/module reload, candidate identity invalidation, or another user authenticates. Killing/reopening the app does not relock it. This is not a per-operation password prompt.
- MIUIX, Material and the dedicated FinaSU theme Home expose the entry. Fields and dialogs use the selected UI components; an unavailable/unrecognized Core still follows the existing native-MIUIX fallback policy.

## Verifier and protocol

The Manager generates a fresh 16-byte salt and derives a 32-byte proof with PBKDF2-HMAC-SHA256 (210,000 iterations). It embeds **SHA-256(proof)**, not the proof or plaintext key, in the chosen LKM before the existing `boot-patch -m` flow. The fixed 68-byte record is:

```text
magic[16] = LUNEXSU-KEY-V1!!
format[4] = little-endian 1 (0 means unconfigured)
salt[16]
SHA256(proof)[32]
```

The config is read-only after module initialization, with no writable module parameter. Unknown nonzero record versions deny authentication. Old LKMs without exactly one supported slot cannot be used with this option; preparation fails rather than silently installing without the key.

UAPI v7 adds `KSU_IOCTL_SUPERKEY_SALT` (150) and `KSU_IOCTL_SUPERKEY_AUTH` (151). Only the package/certificate-verified candidate may use them. The locked candidate still receives a driver FD during app specialization, without the Manager permission flag. Authentication hashes the supplied proof, compares in constant time, and rate-limits candidate attempts to one per two seconds. The ordinary management and root-grant gates continue to require the authenticated Manager identity.

The key is not placed in command-line arguments, preferences, logs, saved navigation state, or the image. Input is memory-only; working character/proof buffers are cleared on exit. Android text fields still create immutable strings, so this does not promise complete JVM-memory erasure. Only the salted verifier record crosses navigation state and is written into the temporary LKM/image.

## Boundaries and recovery

- This is an extra Manager identity gate, not disk encryption or a password barrier against an already-root process. Existing authorized Root applications and init/ksud continue working.
- An image exposes the salt/verifier and permits offline guessing; use a high-entropy key. Image integrity/verified boot remain separate concerns. Someone able to replace Core can replace the verifier or disable the feature.
- Forgetting the key requires reinstalling/reflashing a matching Core without this option, or with a newly configured key. There is no password-only recovery that bypasses package/certificate checks. The installer defaults to off on each visit; it does not preserve a previous image's key automatically.
- Built-in/source-integrated kernels need their own installation-time record configuration; the Manager option currently targets the LKM embedded in patched boot images. It does not retrofit an older running Core.
- A configured custom LKM remains that custom LKM's responsibility when installed without this option. Signed LKMs subject to enforced module-signature verification cannot be byte-patched without being re-signed.

## Validation

Manager JVM tests cover the PBKDF2/verifier vector, input rules, randomized salts, buffer clearing and LKM slot validation. The authorization host suite compiles the production identity/superkey gates against mocked kernel plumbing to check candidate/full-UID restrictions, revocation, rate limiting, allocation/crypto failures and unknown record versions. SHA-256 itself is mocked in that host harness; it does not validate kernel integration.

Before release, test real patched images and direct/OTA installation with matching signed APK/Core, key disabled/enabled, wrong/correct keys, no Core, a different signing certificate, app restart versus reboot, and theme/native UI entry points. Host and build checks are not substitutes for this device coverage.
