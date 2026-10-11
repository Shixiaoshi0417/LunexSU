# FinaSU theme import in LunexSU

## Implementation plan

- [x] Review FinaSU snapshot `fa57d340` and the existing Manager settings,
  font importer, Material/MIUIX themes and page backgrounds.
- [x] Adapt the bounded FPT reader and validate configuration/resources before
  applying. Keep the AES-CBC/ZIP protocol and upstream attribution.
- [x] Publish unique private asset directories and only allowlisted theme
  preferences. Failed preparation and cancellation leave the current theme intact.
- [x] Add a picker, metadata/compatibility confirmation and wallpaper reset to
  the existing Material and MIUIX color/theme pages.
- [x] Render global/per-page image backgrounds and synchronize imported fonts,
  colors and home settings across restart without touching Root policy.
- [x] Test archive boundaries, invalid configuration and settings isolation;
  compile both UIs and run the Manager release/unit/instrumentation checks.

## Compatibility scope

The format is taken from [FinaSU by LyraVoid](https://github.com/LyraVoid/FinaSU),
snapshot `fa57d340f4da3f42fc422eae4994c0b3be7f027c`. Its FPT container consists of a
16-byte IV followed by an AES-CBC encrypted flat ZIP, containing `theme.json`
and optional assets. The format's shared key is compatibility encoding, not
proof that a theme is trusted.

This port maps colors, night mode, palette style/spec/contrast, the existing home
layouts and fonts to LunexSU's current settings. Image wallpapers support the
global background and Home/Superuser/Module/Settings page slots. Unsupported
resources/settings are listed before applying; they cannot change Root grants,
module behavior, update URLs, language, package identity or the UI architecture.
The online theme store, video/audio, per-card image surfaces and custom navigation
icons remain separate upstream systems.

## Usage

Open Settings -> Theme -> Import FinaSU theme (.fpt), choose a local package and
review the name, author, version, resource count and compatibility notices. Apply
uses the same prepared payload that was reviewed. Cancel leaves the current theme
unchanged. Restore default theme resets colors, font, home layout and backgrounds
while retaining the selected UI and unrelated settings.

GIF and animated WebP images use Android's drawable decoder; animation stops when
the page is inactive or the activity is not resumed. Images are downsampled to a
maximum dimension of 2048 pixels for rendering.

The black scrim follows FinaSU's dual dim: `backgroundDim` (default 0.2) applies to
both themes, or with `isDualBackgroundDimEnabled` the light theme uses
`backgroundDayDim` (0.2) and the dark theme `backgroundNightDim` (0.4). In the
Material UI a light theme also follows the visible page's wallpaper brightness,
measured once per image from a 64 px decode: when brightness × (1 − dim) is below
0.5, the neutral roles switch to a dark neutral palette (light text, light system
bar icons) and the painted scrim is raised to the smallest value that keeps that
text at WCAG AA 4.5:1 over a white wallpaper. The pack's dim values are never
rewritten. A dark theme never flips; its readability is left to the night dim, as
in FinaSU. MIUIX applies the dual dim without the brightness adaptation.

Containers follow FinaSU's wallpaper materials. `backgroundOpacity` is the panel
alpha (0 shows the wallpaper fully, 1 is opaque; a pack without it imports as
0.5, like FinaSU). Group (list groups, cards) uses it one-to-one, Raised adds
0.12, Overlay (dialogs, menus) stays at 0.96, and Chrome (top/bottom bars, rail,
pinned tab row) keeps a 0.10 floor and strengthens to at least 0.85 as a large
top bar collapses. Only fills are translucent: each material pairs an opaque
surface tint with opaque content colors, and no `Modifier.alpha` is involved.
A container inside another material draws no second fill, and the rows of a
list group share one group fill. In the Material UI the theme keeps every
surface role opaque (only the canvas is transparent), so components without a
material, such as dialogs, sheets and the theme preview, stay readable. MIUIX
draws cards and list popups with the same `surfaceContainer` role, so the root
Scaffold, which hosts its overlay dialogs and popups, keeps an opaque background
and the 0.96 overlay alpha, while page content gets the group alpha; MIUIX bars
use the chrome material unless their blur backdrop is active. Without a pack
wallpaper both UIs render exactly as before.
Drawables render through Compose's canvas with page clipping, so preloaded pager
backgrounds cannot cover the current page. Expected preparation errors are returned
inside the loading job before being handled; a bad package cannot cancel its dialog
scope and prevent subsequent imports.

## Import boundaries and persistence

The archive is flat: directories, traversal, duplicate names, control characters
and drive-qualified names are rejected before writing. Limits are 128 entries,
1 MiB configuration, 256 MiB expanded resources and 288 MiB container input.
Deeply nested JSON is rejected before object parsing. Image headers and custom
fonts are checked with Android's actual decoders before confirmation.

Validated assets are copied to a new private `theme_packs/<UUID>/` directory. The
complete preference batch is committed only after all copies succeed. A failed
commit attempts to restore the previous batch; asset cleanup retains directories
referenced by the current active theme or selected font. Missing/corrupt saved
theme data disables wallpaper loading rather than following external paths.

Manager wallpaper colors are scoped to MainActivity; module WebUI keeps its
existing opaque palette. Imports use no shell commands, network downloads or Root
calls. Theme JSON fields cannot write arbitrary preferences or authorization data.

## Validation

On 2026-10-11 the clean Manager release/debug/test APK build passed (157 tasks).
All 45 JVM tests passed: 30 container/schema regressions and 15 existing home,
theme and font-ordering tests. The final rendering/error-recovery changes also
passed the release/debug/test builds and the Android integration suite.

On the Android 17 x86_64 `LunexSU_Theme_Test` AVD, all 13 Android cases passed:
cancel/no publication, settings isolation, live custom-font reload, private
per-page assets, persisted selection reload, truncated container, invalid image,
invalid font, staging cleanup, preference failure rollback, asset-copy failure,
partial-pack font retention and theme-only reset/garbage collection.

ADB UI testing of both real Material/MIUIX theme screens passed the system picker,
metadata/warnings, cancellation, application, invalid-file recovery and successful
retry, and restore-default flows. Chinese preview text was checked on Material.
Restarting the actual Manager preserved the imported Stats layout, font and Home
background; visual inspection confirmed the adjacent-page clipping fix. Both About
screens displayed the new artwork and FinaSU/LyraVoid attribution. The crash buffer
was empty. The fixture contains ignored Root/UI/token fields to verify isolation.

The AVD reported `recognized=false uapi=0`. Its theme UI test host is confined to
`src/debug`, protected by the privileged `android.permission.DUMP` permission and
absent from release APKs. It opens the actual screens without relaxing production
capability gates. This verifies theme behavior; it does not claim Root/SELinux,
module or Metamodule runtime operation on a matching Core.

The regression suite covers encrypted-container compatibility, truncation, archive
and JSON limits, field validation and settings isolation. Android integration uses
an isolated private namespace and the platform's image/font decoders. Run on an
emulator or device after building and installing the debug Manager/test APKs:

```sh
adb -s <emulator-serial> shell am instrument -w -e suite theme \
  com.shixiaoshi0417.lunexsu.test/com.shixiaoshi0417.lunexsu.AuthorizationTestRunner
```

This theme suite does not require KernelSU Core. The runner's original Root suite
continues to require a recognized Manager and matching UAPI 6 Core.

For UI reproduction on a disposable emulator with debug/test APKs installed:

```sh
adb -s <emulator-serial> shell am instrument -w -e suite theme_fixture -e ui material \
  com.shixiaoshi0417.lunexsu.test/com.shixiaoshi0417.lunexsu.AuthorizationTestRunner
# Copy the reported cache fixture using adb exec-out run-as, then push it to Downloads.
adb -s <emulator-serial> shell am start -n com.shixiaoshi0417.lunexsu/.ui.ThemeTestActivity
# Use ui=miuix for the second UI; force-stop before changing the host's initial page.
```

## Files

- `manager/.../ui/theme/pack/`: FPT container, schema, importer, state and renderer.
- `manager/.../ui/screen/colorpalette/`: shared import flow and UI entry points.
- `manager/.../ui/theme/{FontConfig,MaterialTheme,MiuixTheme}.kt`: live theme integration.
- `manager/.../ui/MainActivity.kt`: wallpaper composition for main pages.
- `manager/app/src/test/.../ui/theme/pack/`: archive/schema regression tests.
- `manager/app/src/androidTest/`: Android decoder/storage/preference regression suite.
- `manager/app/src/debug/`: privileged ADB host for Core-less theme/About UI testing.
