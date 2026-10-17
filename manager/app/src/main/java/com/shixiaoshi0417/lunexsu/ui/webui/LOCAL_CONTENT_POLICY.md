# Root-bridged module WebUI content policy

`ksu` is deliberately a full-trust module API. Android's
`addJavascriptInterface` exposes it to **every frame**, not just the top page.
Checking `WebView.url` in an interface method does not identify the calling
frame. Never attach this interface to a general-purpose browser or relax the
local-only client, settings, or enforced response CSP independently.

The WebView must support AndroidX WebKit's `MULTI_PROFILE` capability. Otherwise
initialization fails closed before attaching the bridge. Bridged views use only
the versioned `lunexsu-local-modules-v1` profile, never the historical/default
profile. This prevents old service-worker registrations or cache entries from
bypassing interception/CSP. The profile's service-worker network/file/content
requests are independently denied. Never reuse this profile for other browsing.

## Allowed content

- GET requests to `https://mui.kernelsu.org` (case-insensitive scheme/host,
  absent port or exactly `:443`) are intercepted from this module's `webroot`.
  Query strings and fragments are supported. Userinfo, alternate ports,
  lookalike/trailing-dot hosts, backslashes, control characters, dot segments,
  encoded separators and double-encoded paths are rejected.
- Local HTML, JS, CSS, local frames, inline scripts, eval, and the existing
  `ksu` methods remain supported. The module's entire webroot is root-trusted,
  including local SVG/HTML and any HTML it constructs in inherited-origin
  `about:blank`/`srcdoc` frames. The policy is not an HTML sanitizer.
- `ksu://icon/<package>` is a non-main-frame GET-only image endpoint returning
  generated PNG bytes, never a document or arbitrary file. Data URLs are
  permitted only for images, fonts, and media by CSP, not executable sources.
- A user-initiated, non-redirect top-level external HTTP(S) navigation opens
  an ACTION_VIEW/BROWSABLE system handler, never this WebView. User-clicked
  external `_blank` anchors are similarly handed off when the hit-test exposes
  their URL. No fallback WebView or popup WebView is created. Unsupported
  schemes, automatic external navigation, frame navigation to external pages,
  and external redirects are denied. Local `_blank` popups are denied too.

## Intentional compatibility changes

Remote CDN scripts/styles/fonts/images, remote frames, fetch/XHR/WebSockets,
remote forms, workers/service-worker registration, plugins/objects, custom
scheme links, downloads, file/content URL access, and blob/data executable
documents are no longer supported inside the bridged view. Bundle dependencies
in `webroot` instead. `base` elements are disabled. The enforced CSP is attached
to every intercepted response (including errors) so module meta tags cannot
relax it. Missing files and unmatched requests return local errors, never null;
network loads and mixed content are independently disabled. The local loader
does not emit redirects, and the client rejects redirect responses.
Profile isolation also intentionally starts fresh WebUI browser storage once;
old cookies, DOM storage, caches, and service workers are not migrated. An older
WebView engine without `MULTI_PROFILE` must be updated to use module WebUIs.

## Trust boundary and limitations

An installed module and its backing webroot files are already root-trusted;
file ownership/permissions are not newly audited here. A module must not promote
untrusted writable files or symlink targets to executable WebUI content.
Local scripts can intentionally
use `ksu.exec`/`spawn` to access the network or download and execute code; this
is not and cannot be a sandbox for a malicious installed module. Likewise a
module that explicitly embeds attacker-provided strings as executable inline
HTML/JS has promoted those strings to module trust. Inherited-origin empty or
srcdoc frames created by trusted module code share that trust and CSP. The
Manager does not authenticate each JS call or isolate different installed
modules' DOM storage within the shared local-only profile and origin. A WebView/Chromium
engine vulnerability is outside this policy's guarantees.

`onPageStarted` revokes the bridge and disables JS if an unexpected top-level
document slips past navigation handling; this is defense in depth, not caller
authentication or the primary remote-frame defense. NoRootDemo keeps its
non-executing shell substitutes and denied privileged file reads unchanged.

## Tests

`WebUIOriginPolicyTest` is JVM-only. `WebUIIsolationTestRunner` is a separate,
root-free Android instrumentation entry point using an inert reporting probe
instead of the real root bridge. It exercises production settings/client/CSP,
local scripts/frames, remote/data/blob document denial, request misses,
navigation/redirect handling, and bridge revocation. Compile it with the
parent's coordinated Android test build; do not run instrumentation as part
of the local security audit. Renderer tests are authored but require a future
explicitly authorized device run to establish behavior on installed WebViews.
The default instrumentation runner is unchanged; select
`com.shixiaoshi0417.lunexsu.ui.webui.WebUIIsolationTestRunner` when building a
dedicated test APK for such a run, or wire its suite into the existing runner.
`WebUIDemoBridgeTest` is included only in `NO_ROOT_DEMO` JVM tests and verifies
that synchronous bridge execution remains empty/non-executing via DemoShell.
