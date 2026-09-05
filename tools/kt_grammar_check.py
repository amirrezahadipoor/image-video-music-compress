#!/usr/bin/env python3
"""Kotlin lexer-state gate: comments, string literals, and R.* resource ids.

Why this exists: five consecutive commits were red because prose inside a KDoc
contained "/*" (e.g. `schemas/*.json`). Kotlin block comments NEST, so that pair
opened a comment that never closed and swallowed the rest of the file -- the
compiler said `Unclosed comment` at EOF and `Expecting member declaration` for
code that was perfectly fine. Gradle then never type-checked anything else, so
the real errors were discovered one push at a time.

Both of those costs are avoidable in ~1 second, without Gradle:
  1. block comments balance, with nesting and line/string states tracked;
  2. braces/brackets/parens balance outside comments and literals;
  3. every R.<type>.<name> referenced by Kotlin resolves to a real resource.

Exit code 1 on any finding. This is a syntax/consistency gate, NOT a compiler --
it cannot catch a wrong type or a typo in a method name, which is what Gradle
is still for.
"""
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app" / "src"
RES = ROOT / "app" / "src" / "main" / "res"
VALUES_RE = re.compile(r'<(string|plurals|color|dimen|integer|bool|string-array|style)\s+name="([^"]+)"')
REF_RE = re.compile(r'\bR\.(string|plurals|color|dimen|integer|bool|drawable|mipmap|raw|font|xml)\.([A-Za-z0-9_]+)')
TYPED_DIRS = ("drawable", "mipmap", "raw", "font", "xml", "anim", "transition", "menu")


def scan(src: str):
    """Return problems, tracking comment nesting / string / char / template states."""
    i, n, line = 0, len(src), 1
    cdepth = 0
    stack = []
    errs = []
    while i < n:
        two = src[i:i + 2]
        if cdepth:
            if two == "/*":
                cdepth += 1
                i += 2
                continue
            if two == "*/":
                cdepth -= 1
                i += 2
                continue
            if src[i] == "\n":
                line += 1
            i += 1
            continue
        if two == "//":
            j = src.find("\n", i)
            j = n if j < 0 else j
            line += src.count("\n", i, j)
            i = j
            continue
        if two == "/*":
            cdepth += 1
            i += 2
            continue
        if src.startswith('"""', i):
            opened = line
            i += 3
            while i < n and not src.startswith('"""', i):
                if src[i] == "\\":
                    line += src.count("\n", i, i + 2)
                    i += 2
                    continue
                if src[i] == "\n":
                    line += 1
                i += 1
            if i >= n:
                errs.append(f"unterminated raw string opened at line {opened}")
            i += 3
            continue
        if src[i] in "\"'":
            quote, opened = src[i], line
            i += 1
            while i < n and src[i] != quote:
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == "\n":
                    errs.append(f"unterminated {quote} literal opened at line {opened}")
                    break
                if quote == '"' and src[i] == "$" and src[i + 1:i + 2] == "{":
                    depth = 1
                    i += 2
                    while i < n and depth:
                        if src[i] == "{":
                            depth += 1
                        elif src[i] == "}":
                            depth -= 1
                        elif src[i] == "\n":
                            line += 1
                        i += 1
                    continue
                i += 1
            i += 1
            continue
        if src[i] in "{([":
            stack.append((src[i], line))
        elif src[i] in "})]":
            if not stack:
                errs.append(f"unmatched '{src[i]}' at line {line}")
            else:
                opener, ol = stack.pop()
                if "{([".index(opener) != "})]".index(src[i]):
                    errs.append(f"'{opener}' at line {ol} closed by '{src[i]}' at line {line}")
        if src[i] == "\n":
            line += 1
        i += 1
    if cdepth:
        errs.append(
            f"UNCLOSED block comment (depth {cdepth}) at EOF line {line} "
            "-- look for '/*' inside the comment text, e.g. a glob like schemas/*.json "
            "or a wildcard mime type like \"video/*\"; Kotlin comments nest, so it opened "
            "a comment inside your comment"
        )
    for opener, ol in stack:
        errs.append(f"unclosed '{opener}' opened at line {ol}")
    return errs


def resources():
    have = {}
    for xml in sorted(RES.glob("values*/*.xml")):
        for m in VALUES_RE.finditer(xml.read_text(encoding="utf-8")):
            have.setdefault(m.group(1), set()).add(m.group(2))
    for typed in TYPED_DIRS:
        for d in [RES / typed] + sorted(RES.glob(f"{typed}-*")):
            if d.is_dir():
                have.setdefault(typed, set()).update(
                    p.stem for p in d.rglob("*") if p.is_file() and not p.name.startswith(".")
                )
    return have


def main():
    files = sorted(SRC.rglob("*.kt"))
    bad = 0
    for f in files:
        for e in scan(f.read_text(encoding="utf-8")):
            print(f"::error file={f.relative_to(ROOT)}::{e}")
            bad += 1

    have = resources()
    missing = 0
    for f in files:
        txt = f.read_text(encoding="utf-8")
        for m in REF_RE.finditer(txt):
            kind, name = m.groups()
            if name == "id":
                continue
            if kind not in have or name not in have[kind]:
                ln = txt.count("\n", 0, m.start()) + 1
                print(f"::error file={f.relative_to(ROOT)},line={ln}::"
                      f"R.{kind}.{name} has no matching resource")
                missing += 1

    # app metadata sanity: the two values that decide install/upgrade order
    gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    vc = re.search(r"versionCode\s*=\s*(\d+)", gradle)
    mn = re.search(r"minSdk\s*=\s*(\d+)", gradle)
    print(f"versionCode={vc.group(1) if vc else '?'}, minSdk={mn.group(1) if mn else '?'}")
    if mn and int(mn.group(1)) < 24:
        print("::error file=app/build.gradle.kts::minSdk below 24 needs v1 (JAR) signing "
              "enabled for the APK to install; CI only checks the APK Signing Block")
        missing += 1

    # the shipped APK in the workspace, if a build was run here, must be a zip
    for apk in sorted(ROOT.glob("apk/*.apk")) + sorted(ROOT.glob("app/build/outputs/apk/*/*/*.apk")):
        with zipfile.ZipFile(apk) as z:
            need = [n for n in ("AndroidManifest.xml", "resources.arsc") if n not in z.namelist()]
            if need:
                print(f"::error::{apk.name} is missing {need}")
                missing += 1

    if bad or missing:
        print(f"FAILED: {bad} syntax problem(s), {missing} reference/build problem(s)")
        return 1
    print(f"OK: {len(files)} Kotlin files balanced; every R.* reference resolves")
    return 0


if __name__ == "__main__":
    sys.exit(main())
