# PR build signing policy

Pull-request builds compile and test changes without creating or transferring a
private signing identity. Their `manager-pr-compile-only` APK is unsigned. Their
Core certificate binding is deliberately invalid (a one-byte DER certificate is
not possible). These artifacts are **not installable Manager/Core pairs**.

For an installable test package, first review the source on a trusted branch and
explicitly run the normal `Build Manager` workflow there. Its existing production
signing path and Core certificate verification remain unchanged. The optional
No Root UI demo remains separately named and signed; it has no native root payload.

## Previous PR packages

Historical PR temporary keys passed through ordinary job outputs must be treated
as exposed. Do not use those PR Core/Manager pairs as trusted installations. A new
build does not revoke a private key already copied from a log, and deleting logs
cannot retract it. Recovery/replacement on a device requires the owner's normal
trusted recovery procedure; do not assume a differently signed Manager can replace
the installed PR Manager or control its Core.

Historical log deletion, artifact withdrawal and device replacement are separate
operational actions requiring an explicitly checked scope. Never put recovered
private keys, passwords or log excerpts containing them into issues, PR comments
or regression fixtures. Signing regressions use public constants and synthetic
fixtures only; they do not retrieve historical leaked credentials.
