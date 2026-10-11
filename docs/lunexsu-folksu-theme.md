# FolkSU theme and home layouts in LunexSU Manager

This note records what was ported from [FolkSU](https://github.com/LyraVoid/FolkSU)
(GPL-3.0, reference snapshot `fa57d340`, 2026-10-10) into the LunexSU Manager, how it
fits the Material/MIUIX split, and what was deliberately left out. Ported files carry
an `Adapted from FolkSU` header. LunexSU is GPL-3.0 as well, so the licenses match.

## Shared theme settings (both UIs)

All values live in the existing `settings` SharedPreferences and flow through
`SettingsRepository` → `ThemeController.getAppSettings()` → `KernelSUTheme`. Both
`MainActivityViewModel` and `WebUIActivity` observe the keys, so changes apply at once.

| Key | Meaning | Material | MIUIX |
| --- | --- | --- | --- |
| `color_mode` | system / light / dark (MIUIX adds its Monet variants 3–5) | yes | yes |
| `amoled_black` | pure black in any effective dark theme | full scheme | page canvas only |
| `use_dynamic_color` | Monet from the system wallpaper | yes | MIUIX keeps `miuix_monet` |
| `key_color` | custom seed color, `0` = none | yes | yes (Monet on) |
| `color_style`, `color_spec` | MaterialKolor palette style / spec | yes | yes |
| `color_contrast` | STANDARD / MEDIUM / HIGH | yes | not supported by MIUIX |
| `font_mode`, `custom_font_file` | system font or an imported font | Material typography | MIUIX text styles |
| `home_layout_style` | circle / kernelsu (grid) / focus / dashboard_ui / stats | yes | MIUIX keeps its home |
| `stats_top_layout` | status card or grid board atop the Stats layout | yes | – |
| `home_hide_working_mode` | hide the LKM/GKI tag | yes | – |

Migration: the old `color_mode = 6` (dark AMOLED) is rewritten once to
`color_mode = 2` + `amoled_black = true`. `use_dynamic_color` defaults to
`key_color == 0`, so existing users keep the same colors.

## Material UI

* `MaterialExpressiveTheme` with `MotionScheme.expressive()`, the FolkSU continuous-corner
  shape ladder (`FolkShape`, needs `androidx.graphics:graphics-shapes`), the line-break aware
  typography in the chosen font, pinned semantic error colors and a stronger dark ripple.
* Press feedback: `folkPressScale` + haptics on `TonalCard`, segmented list rows, the tab row,
  dialog/back buttons and the log sheet (`FolkButtons`, `FolkPress`, `FolkMotion`).
* Home layouts Circle, Grid, Focus, Dashboard, Stats with count cards, facts tiles, wavy
  progress rings, wave charts and a donut chart. They read the same `HomeUiState` as before
  (root state still from `rootAvailable()`), the real allowlist count from
  `Natives.getSuperuserCount()` and enabled modules from `ModuleViewModel`. Live device
  metrics (battery, thermal zones, cpufreq, `/proc/meminfo`, `statvfs`) are sampled only
  while Focus/Stats is on screen. Without a full-featured kernel the counts read "Unavailable".
* Theme page: Monet switch, color row with restore-default, light/dark/system buttons,
  AMOLED switch, palette style/spec/contrast, font page, home layout picker with
  miniature previews.

## Not ported

* FolkSU's original per-card photo surfaces and online theme store remain separate
  systems. LunexSU now adapts its FPT reader and settings mapping with a shared
  importer and global/per-page image backgrounds; see [FPT import](lunexsu-fpt-import.md).
* Music/vibration media settings, custom navigation-bar icons and liquid-glass bar tuning.
* The bundled "Xiaolai" font (`APP_DEFAULT` mode): LunexSU keeps the system font as the
  default instead of shipping a large CJK font with its own branding.
* FolkSU's `CapabilityRepository` root probing; LunexSU's existing root checks are unchanged.
