#!/usr/bin/env python3
"""Prevent new parallel implementations of shared controls and silent exception fallbacks."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
failures = []
raw = re.compile(r"(?<![\w])(?:AlertDialog\.Builder|Button|ImageButton|ProgressDialog|EditText|SwitchCompat|RadioButton|CheckBox)\s*\(|Toast\.makeText\s*\(")
for file in (root / "app/src/main/java").rglob("*.kt"):
    if file.name == "UuComponents.kt":
        continue
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
for file in (root / "app/src/main/res/layout").glob("*.xml"):
    if re.search(r"<(?:Button|ImageButton|EditText|CheckBox|RadioButton|androidx\.appcompat\.widget\.SwitchCompat)(?=[\s>])", file.read_text()):
        failures.append(f"{file.relative_to(root)}: use shared XML control classes")
if failures:
    print("\n".join(failures))
    sys.exit(1)
print("Shared UI and exception-fallback boundaries: OK")
