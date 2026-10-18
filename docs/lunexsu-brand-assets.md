# LunexSU brand assets

## Plan

- [x] Inventory launcher, splash, in-app, website, Fastlane and README brand marks.
- [x] Use the supplied illustration to create an unmasked square master and a
  transparent monochrome derivative with the built-in imagegen tool.
- [x] Replace brand resources and check the actual Android launcher mask.
- [x] Rewrite the project README in Chinese with an explicit English link and
  credit the FinaSU UI in both languages and both About page implementations.
- [x] Run the Manager clean release build, unit tests, instrumentation
  compilation and website production build before updating PR #2.

## Source and layout

The illustration was supplied by the project owner. The processed masters are
`assets/lunexsu-logo.png` and `assets/lunexsu-monochrome.png`. The first is an
opaque square with a white background; the second is a transparent silhouette
for Android themed icons. Neither contains a baked launcher mask.

Android uses a white background and an inset foreground within the 108 dp
adaptive-icon canvas. The launcher supplies the final circle, rounded square or
other device mask. The splash and Compose images use the same colored artwork;
the Compose images are fitted to their bounds without a monochrome tint.

Both adaptive foregrounds use a one-sixth fractional inset, equivalent to the
original 18 dp on a 108 dp canvas. A fixed dp inset shrank the artwork excessively
when package viewers or search results drew a small icon. Fractional insets keep
the same artwork-to-bounds ratio across preview sizes and densities without
changing the PNGs, splash artwork or in-app images. The host branding guard checks
this geometry; the optional Android rendering suite checks actual drawable bounds.
Compilation alone does not establish behavior in a particular third-party viewer.

Reference: [Android adaptive icon design](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

The website logo/favicon, Fastlane icon and Manager bitmap resources are resized
exports of these masters. Functional UI icons retain their existing meanings.

## Imagegen prompts

Mode: built-in tool, editing the user-supplied image.

Color master: remove the black border and baked rounded-square frame; fill the
square to all four corners with white; preserve the cat girl, pose, accessories,
tail and decorative marks; center the illustration with generous safe padding;
do not add text, shadows, masks or new content.

Monochrome master: retain the colored master's canvas, placement and outer
contour; convert the illustration and marks to a flat black silhouette with a
transparent exterior and tail loop; no background tile, gray fill or new content.

## UI attribution

Manager themes, shared palette/font settings and the five Material home layouts
were adapted from [FinaSU by LyraVoid](https://github.com/LyraVoid/FinaSU), snapshot
`fa57d340`. See [the port inventory](lunexsu-folksu-theme.md). The original source
headers and GPL attribution remain in place.

## Validation

On 2026-10-11 the Manager clean release/debug/test APK build passed (157 tasks),
with 45 JVM tests passing. Subsequent rendering/error-recovery fixes passed the
release/debug/test builds and the Android theme suite again. The website passed
`bun install` and `bun run docs:build`; its generated logo and favicon matched
the source exports. Resource XML, README language links and Git whitespace checks
passed.

The Android 17 x86_64 test AVD `LunexSU_Theme_Test` displayed the complete colored
illustration within the launcher's circular mask. Both About implementations
displayed LunexSU artwork and the FinaSU/LyraVoid source link. Screenshots are
generated, ignored artifacts in `dist/theme-validation/`, including `launcher.png`,
`material-about.png` and `miuix-about.png`. FPT runtime coverage is recorded in
[the import validation](lunexsu-fpt-import.md#validation).
