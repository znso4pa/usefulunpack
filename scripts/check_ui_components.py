#!/usr/bin/env python3
"""Prevent new parallel implementations of shared controls and silent exception fallbacks."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
failures = []
raw = re.compile(r"(?<![\w])(?:AlertDialog\.Builder|(?:AppCompat|Material)?Button|ImageButton|ProgressDialog|EditText|SwitchCompat|RadioButton|CheckBox|RadioGroup|SeekBar|ScrollView|HorizontalScrollView|FloatingActionButton|ProgressBar|PopupMenu)\s*\(|Toast\.makeText\s*\(")

def direct_control_bodies(source):
    """Ignore literals, comments, nested callbacks and layout params."""
    literals = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
    masked = literals.sub(lambda match: "".join("\n" if c == "\n" else " " for c in match[0]), source)
    pattern = r"\bUu(?:Button|IconButton|TextField|Switch|CheckBox|RadioButton|Slider|ColorButton|ChoiceCell)\([^\n]*?\)\.apply\s*\{"
    for match in re.finditer(pattern, masked):
        depth = 1
        visible = []
        for char in masked[match.end():]:
            if char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    break
            visible.append(char if depth == 1 else ("\n" if char == "\n" else " "))
        yield match.end(), "".join(visible)

appearance_call = re.compile(r"\b(?:setTextColor|setHintTextColor|setBackgroundColor|setBackgroundResource|setPadding|setColorFilter)\s*\(|(?<![.\w])(?:background|backgroundTintList|textSize|minHeight|minimumHeight|minWidth|minimumWidth|imageTintList)\s*=(?!=)")
if "--self-test" in sys.argv:
    cases = [
        ('UuButton(ctx).apply { background = custom }', True),
        ('UuButton(ctx).apply { setPadding(1, 2, 3, 4) }', True),
        ('UuButton(ctx).apply { // " unbalanced quote {\n textSize = 12f }', True),
        ('UuButton(ctx).apply { text = "background = example"; setOnClickListener { label.textSize = 12f } }', False),
        ('UuButton(ctx).apply { layoutParams = LayoutParams(0, 1).apply { width = 48 } }', False),
    ]
    for source, expected in cases:
        found = any(appearance_call.search(body) for _, body in direct_control_bodies(source))
        assert found == expected, source
    print("Shared-control boundary regression cases: OK")
    sys.exit(0)

for file in (root / "app/src/main/java").rglob("*.kt"):
    source = file.read_text()
    if re.search(r"addFlags\([^\n]*FLAG_DIM_BEHIND", source):
        failures.append(f"{file.relative_to(root)}: system dimming must not darken the independent tab strip")
    if re.search(r"\b(?:rootToTab|blockBehind)\s*=\s*false", source):
        failures.append(f"{file.relative_to(root)}: every modal must stay on its opening tab and block background input")
    if file.name == "UuComponents.kt":
        continue

    if file.name != "Diagnostics.kt" and re.search(r"\.(?:delete|deleteRecursively)\(\)", re.sub(r"//[^\n]*", "", source)):
        failures.append(f"{file.relative_to(root)}: use deleteOutputChecked and choose main deletion or cleanup semantics")
    for offset, body in direct_control_bodies(source):
        # Strip line comments before matching; literals and nested code are already masked.
        body = re.sub(r"//[^\n]*", "", body)
        for override in appearance_call.finditer(body):
            line = source[:offset + override.start()].count("\n") + 1
            failures.append(f"{file.relative_to(root)}:{line}: shared control appearance belongs in UuComponents")
    for number, line in enumerate(file.read_text().splitlines(), 1):
        if line.lstrip().startswith(("//", "*")):
            continue
        if file.name != "Diagnostics.kt" and re.search(r"(?:import\s+kotlin\.concurrent\.thread|kotlin\.concurrent\.thread\s*\()", line):
            failures.append(f"{file.relative_to(root)}:{number}: use featureThread to retain chain context")
        if file.name not in {"FeatureUi.kt", "DiagnosticsUi.kt"} and re.search(r"\brunOnUiThread\s*\{", line):
            failures.append(f"{file.relative_to(root)}:{number}: use runFeatureOnUiThread to retain chain context")
        if raw.search(line):
            failures.append(f"{file.relative_to(root)}:{number}: use a shared UI control")
        # TrGuess's inflate probe intentionally treats invalid candidate data as a negative match.
        if file.name != "TrGuess.kt" and re.search(r"\brunCatching\s*\{", line):
            failures.append(f"{file.relative_to(root)}:{number}: use observedAttempt or handle the failure explicitly")
# XML control appearance belongs to the shared class; hosts retain backdrop registration.
control_names = {"UuButton", "UuIconButton", "UuTextField", "UuSwitch", "UuCheckBox", "UuRadioButton", "UuFab", "UuProgressBar"}
appearance = {"background", "backgroundTint", "padding", "paddingStart", "paddingEnd", "paddingLeft", "paddingRight", "paddingTop", "paddingBottom", "textColor", "textSize", "tint", "minWidth", "minHeight", "textAllCaps", "progressTint", "indeterminateTint"}
raw_xml = {"Button", "ImageButton", "EditText", "CheckBox", "RadioButton", "SeekBar", "ScrollView", "HorizontalScrollView", "ProgressBar", "androidx.appcompat.widget.SwitchCompat", "com.google.android.material.floatingactionbutton.FloatingActionButton"}
for file in (root / "app/src/main/res/layout").glob("*.xml"):
    for element in ET.parse(file).iter():
        if element.tag in raw_xml:
            failures.append(f"{file.relative_to(root)}: use shared XML control classes ({element.tag})")
        if element.tag.rsplit(".", 1)[-1] in control_names:
            for attr in element.attrib:
                if attr.rsplit("}", 1)[-1] in appearance:
                    failures.append(f"{file.relative_to(root)}: move {attr.rsplit('}', 1)[-1]} into {element.tag}")
# A focusable bookmark button disables the folder row's ListView item click.
file_row = ET.parse(root / "app/src/main/res/layout/item_file.xml")
android = "{http://schemas.android.com/apk/res/android}"
star = next(element for element in file_row.iter() if element.get(android + "id") == "@+id/btnStar")
if star.get(android + "focusable") != "false":
    failures.append("item_file.xml: bookmark button must not take focus from folder navigation")
theme = ET.parse(root / "app/src/main/res/values/themes.xml")
style = theme.find("style[@name='Theme.UsefulUnpack.Dialog']")
if style.find("item[@name='android:backgroundDimEnabled']").text != "false":
    failures.append("Dialog theme: DialogHost owns the content-only scrim; system dim must stay disabled")
for name in ("android:background", "android:windowBackground"):
    if style.find(f"item[@name='{name}']").text != "@android:color/transparent":
        failures.append(f"Dialog theme: {name} must stay transparent behind the single rounded panel")
# The home page is fixed; only its recent/bookmark child containers may scroll.
home_layout = ET.parse(root / "app/src/main/res/layout/folder_view.xml")
home = next(element for element in home_layout.iter() if element.get(android + "id") == "@+id/homeRoot")
if home.tag != "FrameLayout":
    failures.append("folder_view.xml: the home outer panel must not scroll")
grid = next(element for element in home.iter() if element.get(android + "id") == "@+id/homeGrid")
if grid.get(android + "layout_height") != "match_parent":
    failures.append("folder_view.xml: homeGrid must fill the fixed home viewport")
if failures:
    print("\n".join(failures))
    sys.exit(1)
print("Shared UI and exception-fallback boundaries: OK")
