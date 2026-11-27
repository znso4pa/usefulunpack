#!/usr/bin/env python3
"""Check locale key parity, duplicate resources and tutorial title/body boundaries."""
import argparse
from pathlib import Path
import re
import tempfile
import xml.etree.ElementTree as ET

LOCALES = ("values", "values-zh-rCN", "values-zh-rTW", "values-ja")


def validate(directory):
    errors = []
    keys = {}
    tutorial_counts = {}
    for locale in LOCALES:
        path = directory / locale / "strings.xml"
        try:
            root = ET.parse(path).getroot()
        except (OSError, ET.ParseError) as error:
            errors.append(f"{locale}: cannot parse strings.xml ({type(error).__name__})")
            continue
        if root.tag != "resources":
            errors.append(f"{locale}: expected resources root")
        found = set()
        for resource in root:
            name = resource.get("name")
            if not name:
                errors.append(f"{locale}: unnamed {resource.tag}")
                continue
            kind = "array" if resource.tag in ("string-array", "integer-array", "array") else resource.tag
            key = (kind, name)
            if key in found:
                errors.append(f"{locale}: duplicate {kind}/{name}")
            found.add(key)
            if resource.tag == "string-array" and name == "help_tutorials":
                tutorial_counts[locale] = len(resource)
                for index, item in enumerate(resource, 1):
                    parts = re.split(r"\\{1,2}n|\n", "".join(item.itertext()), maxsplit=1)
                    if len(parts) != 2 or not all(part.strip() for part in parts):
                        errors.append(f"{locale}: help_tutorials item {index} needs Title\\nBody")
        keys[locale] = found
        if locale not in tutorial_counts or tutorial_counts[locale] == 0:
            errors.append(f"{locale}: missing or empty help_tutorials")
    baseline = keys.get(LOCALES[0])
    if baseline is not None:
        for locale, found in keys.items():
            for kind, name in sorted(baseline - found):
                errors.append(f"{locale}: missing {kind}/{name}")
            for kind, name in sorted(found - baseline):
                errors.append(f"{locale}: extra {kind}/{name}")
            if tutorial_counts.get(locale) != tutorial_counts.get(LOCALES[0]):
                errors.append(f"{locale}: help_tutorials item count differs from values")
    return errors


def self_test():
    valid = '<resources><string name="label">Text</string><string-array name="help_tutorials"><item>Title\\nBody</item></string-array></resources>'
    cases = [
        (valid, False),
        (valid.replace('Title\\nBody', 'Title\\\\nBody'), False),
        (valid.replace('<string name="label">Text</string>', ''), True),
        (valid.replace('name="label"', 'name="other"'), True),
        (valid.replace('</resources>', '<string name="label">Duplicate</string></resources>'), True),
        (valid.replace('Title\\nBody', 'Only a title'), True),
        (valid.replace('Title\\nBody', '\\nBody'), True),
        (valid.replace('Title\\nBody', 'Title\\n'), True),
        (valid.replace('</string-array>', '<item>Second\\nBody</item></string-array>'), True),
        (valid.replace('</resources>', ''), True),
    ]
    with tempfile.TemporaryDirectory(prefix="uu-resource-check-") as temp:
        directory = Path(temp)
        for locale in LOCALES:
            (directory / locale).mkdir()
            (directory / locale / 'strings.xml').write_text(valid, encoding='utf-8')
        for text, should_fail in cases:
            (directory / LOCALES[-1] / 'strings.xml').write_text(text, encoding='utf-8')
            if bool(validate(directory)) != should_fail:
                raise SystemExit('Resource checker mutation regression failed')
    print(f'Resource checker: {len(cases)} mutation cases passed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--res-dir', type=Path, default=Path(__file__).resolve().parents[1] / 'app/src/main/res')
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    errors = validate(args.res_dir)
    if errors:
        raise SystemExit('\n'.join(errors))
    print('Four-locale keys, unique resources and tutorial sections: OK')


if __name__ == '__main__':
    main()
