# Input and keyboard layout audit

Run the offline ownership and regression guards:

```sh
python3 tests/ime/test_input_layouts.py
```

The branding/packaging workflow also runs these guards on pushes and pull requests.

These inspect production layout ownership and modifier placement. They **do not**
render Compose, open a keyboard, or prove device layout behavior. The inventory
covers the current leaf-input API calls; a new input API or wrapper also needs a
manual ownership audit. `LUNEXSU_LAYOUT_REVISION=HEAD` optionally checks a Git
revision instead of the working tree, useful for reproducing the missing guards.

## Ownership

| Scenario | Keyboard / scrolling owner |
| --- | --- |
| Install, Superkey setup, advanced block-module list | Install page viewport, both UI modes |
| Template ID/name/author/description and root profile fields | Template page viewport, both UI modes |
| App/authorization duration and UID/GID/root profile fields | App profile viewport, both UI modes |
| Script arguments | Existing IME-padded scrolling argument form, both UI modes |
| SUSFS version/cmdline/path/kstat/list editors | Dialog host plus bounded, scrolling shared form; action row remains outside it |
| Superkey unlock and errors | Dialog host plus scrolling body above actions |
| UID/GID numeric edit, scale, SELinux domain/rules | Native overlay or Material dialog plus scrolling content |
| Download URL, theme author filters, WebUI JS prompts | Dialog host plus scrolling content |
| Module shortcut name/icon and group selector search | Existing IME-aware sheet/overlay and scrolling content |
| Material app/module/log/theme/repository search | Expanded search passes safeDrawing bottom insets (including IME) to each result list/grid; repository list now forwards them |
| Native app/module/log search | Existing result IME reservation; app/module use max(navigation, IME), logs use an IME footer |
| Native module-repository search | Result viewport reserves navigation and IME insets |
| Native theme-store search | Grid viewport reserves bottom navigation/caption/IME insets without duplicating header insets |
| Module WebUI HTML inputs | Existing WebView viewport uses IME or safeDrawing insets according to module mode |

The checked-in `OWNERS` map also tracks reusable leaf fields so they do not acquire
padding that belongs to their enclosing viewport. Fina uses the Material layouts.
Search-wrapper callers are inventoried separately: padding a snackbar alone does
not protect its sibling results, and every caller must preserve the bottom inset.

MIUIX 0.9.4's `DialogContentLayout.kt` already consumes IME, navigation and caption
insets for `WindowDialog` and `OverlayDialog`. Material's default modal bottom-sheet
host also handles keyboard insets. Do not add another `imePadding` to those bodies.
The app fixes their scrolling/height constraints instead. Native full-page footers
use `windowInsetsBottomHeight` so a consumed keyboard inset does not leave a second
navigation-sized gap. There is deliberately no app-wide `imePadding`.

## Device follow-up (not performed by the host guards)

Use a disposable debug build. No root operation is needed for the input checks;
do not confirm real install, permission, module, or WebUI bridge actions.

- Check MIUIX, Material and Fina; phone portrait/landscape, tablet/freeform,
  gesture/three-button navigation and large font/display scaling.
- Open every input scenario in the table with the keyboard initially closed,
  focus the last field, type long/multiline content, then move between fields.
- Verify the focused cursor can be brought above the keyboard and the last
  content/action is reachable by scrolling; check long SELinux rules and SUSFS
  path/cmdline values as well as Superkey remember/error states.
- Check shortcut/group bottom sheets and search results for no double keyboard
  gap; close/reopen the keyboard and verify normal bottom navigation spacing.
- Check both WebUI inset modes with a trusted local test page, and test its long
  JS prompt without invoking any privileged bridge method.
- Repeat with a hardware/floating keyboard: zero reported IME inset must not
  manufacture extra padding or prevent normal scrolling.

Compilation and host tests cannot replace these real keyboard checks.
