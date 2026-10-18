"""Offline source guards for input owners; these are not device layout tests."""
from pathlib import Path
import os
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
UI = "manager/app/src/main/java/com/shixiaoshi0417/lunexsu/ui/"
REVISION = os.environ.get("LUNEXSU_LAYOUT_REVISION")
INPUT = re.compile(r"\b(?:MiuixTextField|OutlinedTextField|TextField|BasicTextField|InputField|EditText|SegmentedTextField|SuperkeyField|SuperEditArrow)\s*\(")

# Every leaf input must have an explicitly audited viewport or dialog owner.
OWNERS = {
    "component/dialog/DownloadDialog.kt": "resizing dialogs with scrollable content",
    "component/material/SearchBar.kt": "callers apply the expanded search safeDrawing bottom inset",
    "component/material/SegmentedList.kt": "leaf field; profile/template page owns insets",
    "component/miuix/EditText.kt": "leaf field; template page owns insets",
    "component/miuix/ScaleDialog.kt": "MIUIX overlay owns IME; content scrolls",
    "component/miuix/SuperEditArrow.kt": "MIUIX overlay owns IME; content scrolls",
    "component/miuix/SuperSearchBar.kt": "callers reserve IME space for results",
    "component/profile/AppProfileConfigMaterial.kt": "AppProfileMaterial viewport",
    "component/profile/AppProfileConfigMiuix.kt": "AppProfileMiuix viewport",
    "component/profile/AuthorizationConfig.kt": "profile viewport for either UI mode",
    "component/profile/dialogs/MultiSelectDialog.kt": "Material sheet owns IME; list scrolls",
    "component/profile/RootProfileConfigMaterial.kt": "profile/template viewport or scrolling SELinux dialog",
    "component/profile/RootProfileConfigMiuix.kt": "profile/template viewport or scrolling native overlay",
    "screen/home/HomeSuperkey.kt": "resizing dialogs; scrollable body above actions",
    "screen/install/InstallMaterial.kt": "scrolling IME-padded page",
    "screen/install/InstallMiuix.kt": "scrolling IME-padded page",
    "screen/install/InstallSuperkey.kt": "install viewport or Superkey dialog",
    "screen/module/ModuleMaterial.kt": "Material sheet owns IME; shortcut form scrolls",
    "screen/module/ModuleMiuix.kt": "native overlay owns IME; shortcut form scrolls",
    "screen/scriptrun/ScriptRunMaterial.kt": "scrolling IME-padded argument form",
    "screen/scriptrun/ScriptRunMiuix.kt": "scrolling IME-padded argument form",
    "screen/susfs/SusfsComponents.kt": "resizing dialog; bounded form body",
    "screen/susfs/SusfsEditors.kt": "shared scrollable SusfsForm",
    "screen/templateeditor/TemplateEditorMaterial.kt": "scrolling IME-padded page",
    "screen/templateeditor/TemplateEditorMiuix.kt": "scrolling IME-padded page",
    "screen/themestore/ThemeStoreMaterial.kt": "expanded search; scrolling filter dialog",
    "screen/themestore/ThemeStoreMiuix.kt": "IME-padded grid; scrolling filter dialog",
    "webui/WebUIMaterial.kt": "resizing prompt with scrollable body",
    "webui/WebUIMiuix.kt": "native IME-aware prompt with scrollable content",
}


def source(path):
    if REVISION:
        return subprocess.check_output(["git", "show", f"{REVISION}:{path}"], cwd=ROOT).decode("utf-8")
    return (ROOT / path).read_text(encoding="utf-8")


def code(text):
    # Keep offsets but ignore comments, quoted braces and field-looking strings.
    pattern = r'""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/'
    return re.sub(pattern, lambda match: " " * len(match[0]), text, flags=re.S)


def body(text, marker):
    masked = code(text)
    start = masked.index("{", masked.index(marker))
    depth = 1
    for end in range(start + 1, len(masked)):
        depth += (masked[end] == "{") - (masked[end] == "}")
        if depth == 0:
            return text[start + 1:end]
    raise AssertionError("Unclosed body: " + marker)


class InputLayoutTests(unittest.TestCase):
    def test_all_leaf_input_files_have_audited_owners(self):
        if REVISION:
            paths = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", REVISION, UI], cwd=ROOT).decode().splitlines()
        else:
            paths = [path.relative_to(ROOT).as_posix() for path in (ROOT / UI).rglob("*.kt")]
        actual = set()
        for path in paths:
            if path.endswith(".kt") and any(INPUT.search(line) and not re.search(r"\bfun\b", line)
                                            for line in code(source(path)).splitlines()):
                actual.add(path.removeprefix(UI))
        self.assertEqual(set(OWNERS), actual, "Audit the owner of every new or removed input file")

    def test_full_screen_forms_reserve_ime_before_scroll_content(self):
        for path in (
            "screen/install/InstallMaterial.kt", "screen/install/InstallMiuix.kt",
            "screen/appprofile/AppProfileMaterial.kt", "screen/appprofile/AppProfileMiuix.kt",
            "screen/templateeditor/TemplateEditorMaterial.kt", "screen/templateeditor/TemplateEditorMiuix.kt",
            "screen/scriptrun/ScriptRunMaterial.kt", "screen/scriptrun/ScriptRunMiuix.kt",
        ):
            with self.subTest(path=path):
                text = code(source(UI + path))
                self.assertEqual(1, text.count(".imePadding()"))
                if "Miuix.kt" not in path:
                    self.assertLess(text.index(".imePadding()"), text.index(".verticalScroll("))
                else:
                    self.assertLess(text.index(".imePadding()"), text.index(".nestedScroll("))

    def test_native_page_footers_respect_consumed_keyboard_insets(self):
        for path in ("screen/install/InstallMiuix.kt", "screen/appprofile/AppProfileMiuix.kt",
                     "screen/templateeditor/TemplateEditorMiuix.kt"):
            with self.subTest(path=path):
                text = code(source(UI + path))
                self.assertIn("windowInsetsBottomHeight(WindowInsets.navigationBars.union(WindowInsets.captionBar))", text)
                self.assertNotIn(".asPaddingValues()", text)

    def test_theme_search_grid_resizes_without_raw_double_padding(self):
        text = code(source(UI + "screen/themestore/ThemeStoreMiuix.kt"))
        self.assertIn(".imePadding()", text)
        self.assertIn(".windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.captionBar).only(WindowInsetsSides.Bottom))", text)
        self.assertNotIn("bottomInset", text)

    def test_dialog_inputs_have_scrollable_content(self):
        cases = (
            ("component/dialog/DownloadDialog.kt", "fun DownloadDialogMaterial("),
            ("component/dialog/DownloadDialog.kt", "fun DownloadDialogMiuix("),
            ("component/miuix/ScaleDialog.kt", "fun ScaleDialog("),
            ("component/miuix/SuperEditArrow.kt", "fun EditDialog("),
            ("component/profile/RootProfileConfigMaterial.kt", "fun SELinuxDialog("),
            ("component/profile/RootProfileConfigMiuix.kt", "fun SELinuxPanel("),
            ("screen/themestore/ThemeStoreMaterial.kt", "fun ThemeFilterDialogMaterial("),
            ("screen/themestore/ThemeStoreMiuix.kt", "fun ThemeFilterMiuix("),
            ("webui/WebUIMaterial.kt", "is WebUIEvent.ShowPrompt ->"),
            ("webui/WebUIMiuix.kt", "is WebUIEvent.ShowPrompt ->"),
        )
        for path, marker in cases:
            with self.subTest(path=path, marker=marker):
                self.assertIn(".verticalScroll(rememberScrollState())", code(body(source(UI + path), marker)))

    def test_superkey_and_susfs_bound_body_above_actions(self):
        text = code(body(source(UI + "screen/home/HomeSuperkey.kt"), "fun SuperkeyDialog("))
        self.assertIn(".weight(1f, fill = false).verticalScroll(rememberScrollState())", text)
        self.assertGreaterEqual(text.count(".verticalScroll(rememberScrollState())"), 2)
        text = code(source(UI + "screen/susfs/SusfsComponents.kt"))
        self.assertRegex(text, r"Column\(Modifier.weight\(1f, fill = false\)\)\s*\{\s*text\(\)\s*\}")
        # The shared form already scrolls; nesting another vertical scroll is invalid.
        self.assertNotIn(".verticalScroll(", text)
        self.assertIn(".verticalScroll(rememberScrollState())", code(body(source(UI + "screen/susfs/SusfsEditors.kt"), "fun SusfsForm(")))

    def test_every_search_wrapper_caller_is_audited(self):
        expected = {
            "SearchPager": {
                "screen/superuser/SuperUserMiuix.kt": 1, "screen/module/ModuleMiuix.kt": 1,
                "screen/sulog/SulogMiuix.kt": 1, "screen/modulerepo/ModuleRepoMiuix.kt": 1,
            },
            "SearchAppBar": {
                "screen/superuser/SuperUserMaterial.kt": 1, "screen/module/ModuleMaterial.kt": 1,
                "screen/sulog/SulogMaterial.kt": 1, "screen/modulerepo/ModuleRepoMaterial.kt": 1,
                "screen/themestore/ThemeStoreMaterial.kt": 2,
            },
        }
        if REVISION:
            paths = subprocess.check_output(["git", "ls-tree", "-r", "--name-only", REVISION, UI + "screen"], cwd=ROOT).decode().splitlines()
        else:
            paths = [path.relative_to(ROOT).as_posix() for path in (ROOT / UI / "screen").rglob("*.kt")]
        for wrapper, owners in expected.items():
            actual = {path.removeprefix(UI): len(re.findall(r"\b" + wrapper + r"\s*\(", code(source(path))))
                      for path in paths if path.endswith(".kt")}
            self.assertEqual(owners, {path: count for path, count in actual.items() if count},
                             "Audit both empty-query and search-result branches of every caller")

    def test_native_search_results_keep_ime_reservation(self):
        for path in ("screen/superuser/SuperUserMiuix.kt", "screen/module/ModuleMiuix.kt"):
            with self.subTest(path=path):
                text = code(body(source(UI + path), "popupHost ="))
                self.assertIn("WindowInsets.ime.asPaddingValues()", text)
                self.assertRegex(text, r"maxOf\([^)]*imeBottomPadding")
        logs = code(body(source(UI + "screen/sulog/SulogMiuix.kt"), "popupHost ="))
        self.assertIn("WindowInsets.ime.asPaddingValues()", logs)
        self.assertIn("Spacer(Modifier.height(imeBottomPadding))", logs)
        repo = code(body(source(UI + "screen/modulerepo/ModuleRepoMiuix.kt"), "popupHost ="))
        self.assertIn(".navigationBarsPadding()", repo)
        self.assertIn(".imePadding()", repo)
        self.assertLess(repo.index(".imePadding()"), repo.index(".overScrollVertical()"))

    def test_material_search_callbacks_forward_safe_bottom_inset(self):
        # An IME-padded snackbar is a sibling, not protection for the result list.
        shared = code(source(UI + "component/material/SearchBar.kt"))
        self.assertIn("val bottomPadding = SearchBarDefaults.fullScreenWindowInsets.asPaddingValues().calculateBottomPadding()", shared)
        self.assertIn("searchContent(bottomPadding, collapseAndClear)", shared)
        for path, uses in (
            ("screen/module/ModuleMaterial.kt", "bottomInnerPadding = bottomPadding"),
            ("screen/superuser/SuperUserMaterial.kt", "bottom = 16.dp + bottomPadding"),
            ("screen/sulog/SulogMaterial.kt", "bottom = 16.dp + bottomPadding"),
            ("screen/modulerepo/ModuleRepoMaterial.kt", "bottomPadding = bottomPadding"),
            ("screen/themestore/ThemeStoreMaterial.kt", "contentPadding = gridPadding(bottomPadding, top = 8.dp)"),
        ):
            with self.subTest(path=path):
                text = code(source(UI + path))
                start = 0
                while "searchContent =" in text[start:]:
                    start = text.index("searchContent =", start)
                    callback = body(text[start:], "searchContent =")
                    self.assertRegex(callback, r"^\s*bottomPadding,")
                    self.assertIn(uses, callback)
                    start += len(callback) + len("searchContent =") + 2
        repo = code(body(source(UI + "screen/modulerepo/ModuleRepoMaterial.kt"), "fun RepoModuleList("))
        self.assertIn("bottom = 16.dp + bottomPadding", repo)

    def test_sheets_keep_library_insets_and_scrollable_content(self):
        for path, marker in (("screen/module/ModuleMaterial.kt", "fun ModuleShortcutSheet("),
                             ("screen/module/ModuleMiuix.kt", "fun ModuleShortcutDialog("),
                             ("component/profile/dialogs/MultiSelectDialog.kt", "fun <T> MultiSelectDialog(")):
            with self.subTest(path=path):
                text = code(body(source(UI + path), marker))
                self.assertIn(".verticalScroll(rememberScrollState())", text)
                self.assertNotIn(".imePadding()", text)
                self.assertNotIn("contentWindowInsets =", text)
                self.assertNotIn("windowInsetsPadding = false", text)

    def test_activity_resize_and_webview_modes_are_preserved(self):
        for variant in ("main", "noRootDemo"):
            self.assertIn('android:windowSoftInputMode="adjustResize"', source(f"manager/app/src/{variant}/AndroidManifest.xml"))
        text = source(UI + "webui/WebUIScreen.kt")
        self.assertIn("val imeInsets = WindowInsets.ime", text)
        self.assertIn("val drawingInsets = WindowInsets.safeDrawing", text)
        self.assertIn("if (webUIState.isInsetsEnabled) imeInsets.asPaddingValues() else drawingInsets.asPaddingValues()", text)
        # Never shrink the whole app and then pad input screens a second time.
        self.assertNotIn(".imePadding()", code(source(UI + "MainActivity.kt")))


if __name__ == "__main__":
    unittest.main()
