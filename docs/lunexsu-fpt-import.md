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
snapshot `fa57d340f4da3f42fc422eae4994c0b3be7f027c`; the expanded surface mapping and
theme-only home follow `8ba5a6c`. Its FPT container consists of a
16-byte IV followed by an AES-CBC encrypted flat ZIP, containing `theme.json`
and optional assets. The format's shared key is compatibility encoding, not
proof that a theme is trusted.

This port maps colors, night mode, palette style/spec/contrast, home layouts,
fonts and `appLanguage` to LunexSU's allowlisted theme settings. Wallpapers support
global and Home/Superuser/Module/Settings images, including GIF/animated WebP,
and a global video background. Grid working cards, Dashboard heroes and all four
Focus cards support image surfaces. Title images and custom navigation icons use
the theme-only FinaSU Material presentation. Background music has a toolbar playback switch. Unsupported
resources/settings remain listed before applying; no theme can change Root grants,
module behavior, update URLs, package identity or the saved UI preference.
The online theme store is ported (see Theme store below).

A field the pack leaves out takes FinaSU's import default rather than keeping the
previous theme's value: indigo seed with system dynamic colors on, follow-system night
mode, MD3 2021 tonal-spot palette at standard contrast, circle home layout with the list
stats header, working mode shown, and the `classic` color generation mode. Classic keeps
the colors a pack was designed with: the system Monet scheme as is, FolkPatch's fixed
light/dark table for the 20 catalog seeds, and no contrast boost on any regenerated
scheme, so a pack cannot end up in a high-contrast regeneration it never asked for.
Picking a palette style, spec or contrast afterwards switches to `custom`, which
regenerates the scheme with those choices; restoring the default theme also returns to
`custom`. Language is the exception: an omitted `appLanguage` retains the user's locale;
an explicitly empty value follows the system.

## Usage

Open Settings -> Theme -> Import FinaSU theme (.fpt), choose a local package and
review the name, author, version, resource count and compatibility notices. Apply
uses the same prepared payload that was reviewed. Cancel leaves the current theme
unchanged. Restore default theme resets colors, font, home layout, language and backgrounds
while retaining the selected UI and unrelated settings.

GIF and animated WebP images use Android's drawable decoder; animation stops when
the page is inactive or the activity is not resumed. Images are downsampled to a
maximum dimension of 2048 pixels for rendering.

The black scrim follows FinaSU's dual dim: `backgroundDim` (default 0.2) applies to
both themes, or with `isDualBackgroundDimEnabled` the light theme uses
`backgroundDayDim` (0.2) and the dark theme `backgroundNightDim` (0.4). In the
dedicated theme UI a light theme also follows the visible page's wallpaper brightness,
measured once per image from a 64 px decode: when brightness × (1 − dim) is below
0.5, the neutral roles switch to a dark neutral palette (light text, light system
bar icons) and the painted scrim is raised to the smallest value that keeps that
text at WCAG AA 4.5:1 over a white wallpaper. The pack's dim values are never
rewritten. A dark theme never flips; its readability is left to the night dim, as
in FinaSU. Active packs use this Material presentation even with a saved MIUIX preference.

Containers follow FinaSU's wallpaper materials. `backgroundOpacity` is the panel
alpha (0 shows the wallpaper fully, 1 is opaque; a pack without it imports as
0.5, like FinaSU). Group (list groups, cards) uses it one-to-one, Raised adds
0.12, Overlay (dialogs, menus) stays at 0.96, and Chrome (top/bottom bars, rail,
pinned tab row) keeps a 0.10 floor and strengthens to at least 0.85 as a large
top bar collapses. Only fills are translucent: each material pairs an opaque
surface tint with opaque content colors, and no `Modifier.alpha` is involved.
A container inside another material draws no second fill, and the rows of a
list group share one group fill. In the dedicated theme UI the theme keeps every
surface role opaque (only the canvas is transparent), so components without a
material, such as dialogs, sheets and the theme preview, stay readable. Active packs
no longer use the native MIUIX wallpaper path; without a pack, both original UI
implementations remain available.
Drawables render through Compose's canvas with page clipping, so preloaded pager
backgrounds cannot cover the current page. Expected preparation errors are returned
inside the loading job before being handled; a bad package cannot cancel its dialog
scope and prevent subsequent imports.

### Additional FinaSU assets

- `grid_working_card_background.*`: working-card image, opacity/day/night opacity,
  dim, and independent check-icon/status-text/working-mode visibility switches.
- `dashboard_card_bg.*`: Dashboard hero image with independent presence/enable,
  dual opacity and dual dim.
- `focus_card_{kernel,app,device,storage}_bg.*`: Focus card images with shared
  opacity/dim and per-child overrides. Legacy keys and modern
  `surface.<id>.*` fields are accepted; modern fields take precedence.
- `title_image.*`: Home title image, day/night opacity, dim and horizontal offset.
- `navIcons`: Home/SuperUser/AModule/Settings images, enabled by
  `navIconCustomEnabled` or `nav_icon_custom_enabled`. Badges and page actions
   remain unchanged. The theme's rail, docked bar and floating bar use the same assets.
- `video_background.*`: a looping global video with `videoVolume`, allocated only
  for the visible page while the activity is resumed.
- `musicFilename`: an exact archive-local audio name, with `isMusicEnabled`,
  `musicVolume`, `isAutoPlayEnabled` and `isLoopingEnabled`. One activity-scoped
   player is shared across pages; playback pauses in the background. The play/pause
  button toggles playback even when autoplay is disabled.
- `appLanguage`: validated BCP-47 tags; empty follows the system. Android 13+
  uses application locales, while older supported versions recreate the activity
  with a locale-specific context.

With an active pack, the entire Manager uses the FinaSU-derived Material scaffold,
navigation and wallpaper materials, even if the saved UI preference is MIUIX. Its
theme-only home ports FinaSU's compact title toolbar, install/music/reboot actions,
and Circle/Grid/Focus/Dashboard/Stats boards. Root policy and module controllers are
still LunexSU's. Removing the pack restores the saved Material/MIUIX interface;
the import never writes `ui_mode`. Ordinary M3E and MIUIX do not render pack wallpapers,
card images, custom navigation icons, translucent materials or pack-specific palettes.
These effects are scoped to the dedicated Fina theme provider; shared primitives ignore
pack materials outside that presentation. Ordinary MIUIX retains its native cards and bars.
Images retain bounded native decoding and lifecycle-aware animation.

Sound-effect playback, arbitrary unknown surface IDs and layouts without a LunexSU
equivalent are not implemented. Active unsupported sound effects remain a warning;
disabled media defaults do not produce misleading unsupported-key warnings.
Enabled features with missing optional assets also remain warnings. Audio/video
codec availability depends on the device; this does not guarantee pixel-identical
rendering of every store pack or support for every FinaSU extension.

## Theme store

Settings -> Theme -> Theme store lists the online themes of FinaSU's store, which reads
the FolkPatch-compatible index at `https://folk.mysqil.com/api/themes` (adapted from
FinaSU's `ThemeStoreViewModel`/`ThemeDownloader`). Both UIs show a staggered grid of
previews with search, an author filter, a source filter (official / third-party, where
anything not official, such as `github`, counts as third-party) and a phone/tablet filter.
The index lists duplicate ids and plain-HTTP previews: the first entry per id is kept, the
store host's own HTTP links are upgraded to HTTPS, and only HTTPS packages are offered.

A download stages `<name>.fpt` and the `Theme.webp` preview under private
`files/theme_downloads/`, using `.tmp` siblings. Only after the metadata completion
marker is written is the entire directory atomically renamed into
`files/themes/<ID hash>/<revision UUID>/`. Existing completed libraries are still read,
but directories without valid completion metadata are never listed as installed.
Downloads can be paused and resumed (HTTP `Range`) or
cancelled, retry up to three times, check FPT minimum size/AES block alignment and stop at the
importer's 1056 MiB container limit. A finished theme writes `theme_meta.json` and appears
under My themes, which also works offline; a long press deletes it. The view model is
application-scoped, so a download continues when the store page is closed.

An installed ID is current only when its version and download URL match the index.
A changed version or URL offers Download in both UIs. The old revision stays available
until the update is published; failure or cancellation cannot overwrite its payload or
metadata. Publication replaces the local entry by stable ID, without duplicate entries.

Download validation follows FinaSU `d869a70e`: the 16-byte IV is unrestricted,
including all-zero or printable bytes. No IV-content heuristic is used; the
importer checks the decrypted ZIP/configuration before anything is applied.

Downloading never changes a setting. Apply hands the downloaded file to the same
preparation and confirmation as Import FinaSU theme (.fpt): metadata, resource count and
compatibility notices are shown, and nothing is published until the user confirms. The
store requests carry no token: FinaSU's signature-gated API token is not used.

## Navigation

Only the dedicated theme UI follows FinaSU's navigation modes (Settings -> Theme -> Navigation bar
mode): Automatic (side rail in a large window, bottom bar otherwise), Always bottom bar,
Always side rail, and Floating bottom bar, which is the default. The floating bar comes
in the Drawer form (the selected page expands into a labelled pill) or the Standard
capsule, hides three seconds after the last interaction and while content scrolls down,
and pages reserve its height so content and the module install button never sit under
it. The optional glass effect uses the Liquid library's real-time blur on Android 13+.
Over a pack wallpaper the docked bar and the rail are transparent with the chrome scrim,
while the floating bar carries the Raised material. The rail is FinaSU's fixed icon-only
rail. Without an active pack, M3E uses its opaque bottom bar and expandable wide side rail;
MIUIX keeps its own bottom bar and floating bottom bar setting. Theme navigation preferences
do not affect either ordinary interface, and the theme navigation controls are hidden there.

## Import boundaries and persistence

The archive is flat: directories, traversal, duplicate names, control characters
and drive-qualified names are rejected before writing. Limits are 128 entries,
1 MiB configuration, 1 GiB expanded resources and 1 GiB + 32 MiB container input,
matching FinaSU's current limits (store themes reach several hundred megabytes).
Deeply nested JSON is rejected before object parsing. Image headers and custom
fonts are checked with Android's actual decoders before confirmation. Every selected
card/title/navigation image is validated too. Audio/video must contain a non-empty
matching media track; video dimensions are limited to 4096 pixels per axis.

Validated assets are copied to a new private `theme_packs/<UUID>/` directory. The
complete preference batch is committed only after all copies succeed. A failed
commit attempts to restore the previous batch; asset cleanup retains directories
referenced by the current active theme or selected font. Missing/corrupt saved
theme data disables wallpaper loading rather than following external paths.

Manager wallpaper colors are scoped to MainActivity; module WebUI keeps its
existing opaque palette. Imports use no shell commands, network downloads or Root
calls; only the theme store downloads, and it hands the file to the same importer.
Theme JSON fields cannot write arbitrary preferences or authorization data.

## Validation

### Hidden status photos and route isolation (2026-10-13)

The clean Release build, all 125 JVM tests and Android test APK compilation passed.
Photo-backed status cards now have a transparent base and retain their image layout
when the icon, title and mode are all hidden. Ordinary status-card colors are unchanged.
Every secondary theme route owns an opaque backing and its own wallpaper, so transparent
controls cannot expose the covered page's controls. Covered routes pause animated images
and video; native M3E/MIUIX routes keep their existing rendering and navigation behavior.
An optional debug-only pixel suite covers hidden cards at three opacity values, secondary
routes with/without wallpaper, and native presentation. It was compiled, not executed;
no ADB or device validation was performed for these fixes.

### Native UI isolation (2026-10-13)

The clean Release build, all 124 JVM tests and Android test APK compilation passed
after separating the Fina theme provider and navigation from ordinary M3E/MIUIX.
Navigation regressions cover every theme mode, native window-size/floating preferences,
and restoration after leaving the theme presentation. Theme-only assets and materials
have explicit presentation guards. Device rendering was not tested; no ADB was used.

### Theme-only UI expansion (2026-10-13)

The clean Release build, all 124 JVM unit tests and Android test APK compilation passed.
Nine downloaded store samples passed schema/asset-selection checks, including GIF
working cards and Dashboard resources. The remaining sample notices identify missing
fonts or unused legacy Kernel-page images, rather than supported card fields.
Regression tests cover modern/legacy precedence, day/night surfaces, visibility
flags, title/navigation assets, exact media filenames, locale handling and restoring
the saved UI preference after reset. Android decoder/storage cases were extended
for these assets and compiled, but not executed: no ADB or device validation was
performed for this expansion. Visual fidelity, playback and codec compatibility
still require user testing on a device.

### Earlier validation (2026-10-11)

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
- `manager/.../data/themestore/`, `ui/screen/themestore/`, `ui/viewmodel/ThemeStoreViewModel.kt`:
  theme store index, downloader, Material/MIUIX store and My themes pages.
- `manager/.../ui/theme/{MaterialTheme,MiuixTheme}.kt`: ordinary UI palettes, without pack overrides.
- `manager/.../ui/theme/pack/FinaTheme.kt`: isolated pack palette, materials, dim and ripple provider.
- `manager/.../ui/MainActivity.kt`: wallpaper composition for main pages.
- `manager/.../ui/component/bottombar/`: navigation modes, floating bar and rail.
- `manager/.../ui/theme/FptPalette.kt`: FolkPatch classic palette table.
- `manager/app/src/test/.../ui/theme/pack/`: archive/schema regression tests.
- `manager/app/src/androidTest/`: Android decoder/storage/preference regression suite.
- `manager/app/src/debug/`: privileged ADB host for Core-less theme/About UI testing.
