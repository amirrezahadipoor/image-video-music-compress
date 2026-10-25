#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Checks every Android string resource for a valid format string.

Why this exists (audit item G2): a Persian translation had `٪` (ARABIC PERCENT
SIGN) where Android's formatter expects ASCII `%`, and two strings used the
half-converted form `%1$d%` — which does not throw at compile time, it throws
`UnknownFormatConversionException` at *runtime*, on the screen that shows the
percentage. Neither lint nor a key-parity check catches that; a tokenizer does.

Rules enforced for every <string> without formatted="false":
  · `%%` is a literal percent and is consumed as a pair;
  · `%s`, `%d`, `%f`, `%x`, `%o`, `%e`, `%g`, `%c`, `%b`, `%a` (optionally with
    flags/width/precision) and positional forms (`%1$d`, `%2$.1f`) are valid;
  · any other `%` (including `٪` used as a conversion) is an error;
  · mixing positional and non-positional arguments in one string is an error
    (Android throws IllegalFormatException for it at runtime).

It also checks aapt2's *other* silent-until-compile trap: an apostrophe that is
neither escaped (`\'`) nor wrapped in double quotes ("...") makes the resource
fail to flatten with the unhelpful message "Invalid unicode escape sequence in
string" — which is exactly how a one-line string change broke the whole build.

Usage: python3 tools/string_format_check.py [res-dir ...]
Exit code 1 on any problem.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CONVERSIONS = "sdfxoeEgGcbahHnt"  # Chromium/Java Formatter conversions used in resources
FLAGS = "-+ 0,(<"
SPEC = re.compile(
    r"%(?:(\d+)\$)?[-+ 0,(<]*(?:\d+)?(?:\.\d+)?([a-zA-Z%])"
)
TRANSLATED_PERCENTS = "\u066a\u066b\u0640\u06d5"  # ٪ ٫ ـ (common copy-paste traps)


def check(text: str):
    """Yields (offset, reason) for every problem in `text`."""
    positional = 0
    plain = 0
    for match in SPEC.finditer(text):
        start = match.start()
        # Anything between the previous match end and this '%' that ends in '%'
        # means the previous token was a lone percent (caught below).
        conv = match.group(2)
        if conv == "%":
            # `%%` literal — it is not an argument, so it must NOT carry a
            # positional index; `%1$%` is invalid.
            if match.group(1):
                yield start, "positional index on a literal %%"
            continue
        if conv not in CONVERSIONS:
            yield start, f"unknown conversion %{conv}"
            continue
        if match.group(1):
            positional += 1
        else:
            plain += 1
    if positional and plain:
        yield 0, f"mixes positional ({positional}) and non-positional ({plain}) arguments"

    # A '%' that starts no valid spec is the real-world failure: str.format
    # would render it, Android's formatter throws at runtime.
    masked = SPEC.sub(lambda m: " " * len(m.group(0)), text)
    for i, ch in enumerate(masked):
        if ch == "%":
            yield i, "lone '%' is not a format spec (use %% for a literal percent)"
    for ch in TRANSLATED_PERCENTS:
        if ch in text:
            yield text.index(ch), f"non-ASCII percent-like character {ch!r} in a format string"


APOSTROPHE_ESCAPE = "'"


def check_escaping(text: str):
    """aapt2 escaping rules: apostrophe must be \' or the value quoted."""
    stripped = text.strip()
    if len(stripped) > 1 and stripped.startswith('"') and stripped.endswith('"'):
        return
    for i, ch in enumerate(text):
        if ch == "'" and (i == 0 or text[i - 1] != "\\"):
            yield i, "unescaped apostrophe (use \\' or wrap the value in double quotes)"
    # a backslash that is not a known Android escape sequence
    known = set("\\\\\\'\\\"ntu@?")
    for i, ch in enumerate(text):
        if ch == "\\" and (i + 1 >= len(text) or text[i + 1] not in known):
            yield i, f"unknown escape sequence \\{text[i + 1:i + 2]!r}"


def main(argv):
    roots = argv[1:] or ["app/src/main/res"]
    problems = 0
    checked = 0
    for root in roots:
        for path in sorted(Path(root).rglob("strings.xml")):
            try:
                doc = ET.parse(path).getroot()
            except ET.ParseError as exc:
                print(f"{path}: XML ERROR {exc}")
                problems += 1
                continue
            for el in doc:
                if not el.tag.endswith("string"):
                    continue
                if (el.get("formatted") or "").lower() == "false":
                    continue
                if el.get("translatable") == "false":
                    continue
                text = "".join(el.itertext())
                checked += 1
                for offset, reason in check(text):
                    problems += 1
                    print(f"{path}::{el.get('name')} [{offset}] {reason}\n    {text!r}")
                for offset, reason in check_escaping(text):
                    problems += 1
                    print(f"{path}::{el.get('name')} [{offset}] {reason}\n    {text!r}")
    print(f"{checked} format strings checked, {problems} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
