#!/usr/bin/env python3
"""Generate an independent simple-field oracle with Python's standard Formatter.

Usage: python3 scripts/localization_oracle.py > /tmp/uu-localization-oracle.json
Then set UU_LOCALIZATION_ORACLE to that file when running the Kotlin host tests.
Only synthetic text is generated; this does not read user files or use the network.
"""
import json
import argparse
import shutil
import subprocess
import sys
from string import Formatter
from types import SimpleNamespace


def check_printf():
    compiler = shutil.which("clang")
    if compiler is None:
        raise SystemExit("--check-printf requires Clang")
    pairs = [
        ("%s %d", "%2$d %1$s", '\"text\", 1', True),
        ("%1$s %1$s", "%1$s", '\"text\"', True),
        ("%02d %.3f", "%i %g", "1, 1.0", True),
        ("%hhd %hd", "%d %i", "1, 1", True),
        ("%hhu %hu", "%d %i", "1, 1", True),
        ("%f %lf", "%g %e", "1.0, 1.0", True),
        ("%*.*f", "%3$*1$.*2$f", "1, 1, 1.0", True),
        ("%% %s", "%s %%", '\"text\"', True),
        ("%d", "%c", "1", True),
        ("%u", "%x", "1u", True),
        ("%s %d", "%d %s", '\"text\", 1', False),
        ("%*s", "%s", '1, \"text\"', False),
        ("%ld", "%d", "1L", False),
        ("%s", "%ls", '\"text\"', False),
        ("%f", "%Lf", "1.0", False),
        ("%n", "%hn", "(int*)0", False),
    ]
    for source, translation, arguments, compatible in pairs:
        for text, expected in [(source, True), (translation, compatible)]:
            code = ('void probe(const char*, ...) __attribute__((format(printf,1,2)));\n'
                    'void test(void) { probe(' + json.dumps(text) + ', ' + arguments + '); }\n')
            result = subprocess.run([compiler, "-fsyntax-only", "-x", "c", "-Wformat", "-Werror=format",
                                     "-Wno-format-extra-args", "-"], input=code, text=True, capture_output=True)
            if (result.returncode == 0) != expected:
                raise SystemExit(f"Clang reference disagreement for {text!r}: {result.stderr}")
    print(f"Clang reference: {len(pairs)} pairs / {len(pairs) * 2} format/type checks passed", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-printf", action="store_true", help="Also validate the printf reference pairs with Clang")
    if parser.parse_args().check_printf:
        check_printf()
    formatter = Formatter()
    arguments = [f"positional:{index}" for index in range(10)]
    keywords = {"name": "named:name", "count": "named:count", "player": SimpleNamespace(name="named:player.name")}

    def required(text):
        fields = set()
        for _, field, specification, conversion in formatter.parse(text):
            if field is None:
                continue
            assert not specification and conversion is None
            value, _ = formatter.get_field(field, arguments, keywords)
            fields.add(value)
        return fields

    texts = ["plain", "{name}", "{count}", "{0}", "{00}", "{player.name}",
             "{name} {count}", "{count} {name}", "{name} {name}",
             "{{literal}} {name}", "{{{name}}}", "\\{name}"]
    rows = [{"source": a, "translation": b, "different": required(a) != required(b)}
            for a in texts for b in texts]
    print(json.dumps(rows, ensure_ascii=False))


if __name__ == "__main__":
    main()
