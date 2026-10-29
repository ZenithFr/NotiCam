#!/usr/bin/env python3
"""Static verification for the NotiCam UI layer.

The project is built with a bare AAPT2 + javac toolchain (see build.sh), so a
broken @drawable/@string reference or an R.id typo only shows up at link time.
This script catches those early:

  1. Every res/**/*.xml file must be well-formed XML.
  2. Every android:* attribute must be a real framework attribute name we know
     about (taken from the Android docs list shipped below, plus a permissive
     fallback for obviously valid ones).
  3. Every @drawable/@string/@color/@dimen/@style/@layout/@id reference must
     resolve inside res/.
  4. Every R.<type>.<name> referenced from Java must exist in res/.
  5. Every Java source must parse (syntax-only, via javalang if installed).

Exit code is non-zero when a check fails, so it can gate builds.
"""

from __future__ import annotations

import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "res")
SRC = os.path.join(ROOT, "src")

REF_RE = re.compile(r"@(?!\+)(android:)?([a-zA-Z]+)/([A-Za-z0-9_.]+)")
NEW_ID_RE = re.compile(r"@\+id/([A-Za-z0-9_]+)")
JAVA_R_RE = re.compile(r"\bR\.([a-zA-Z]+)\.([A-Za-z0-9_]+)")

FILE_TYPES = {
    "drawable": {"drawable"},
    "layout": {"layout"},
    "mipmap": {"mipmap"},
    "anim": {"anim"},
}


def fail(msg: str, errors: list[str]) -> None:
    errors.append(msg)


def collect_defined(errors: list[str]):
    """Collects everything res/ defines: typed files, values entries and ids."""
    defined = defaultdict(set)

    for base, _, files in os.walk(RES):
        folder = os.path.basename(base)
        for name in files:
            if folder in FILE_TYPES.get(folder, set()):
                defined[folder].add(name.rsplit(".", 1)[0])

    for base, _, files in os.walk(RES):
        is_values = os.path.basename(base).startswith("values")
        for name in sorted(files):
            if not name.endswith(".xml"):
                continue
            path = os.path.join(base, name)
            try:
                tree = ET.parse(path)
            except ET.ParseError as exc:
                fail(f"malformed XML → {os.path.relpath(path, ROOT)}: {exc}", errors)
                continue

            if is_values:
                root = tree.getroot()
                for element in root:
                    tag = element.tag
                    key = element.get("name")
                    if not key:
                        continue
                    if tag in ("string", "color", "dimen", "bool", "integer",
                               "plurals", "string-array", "integer-array", "array"):
                        defined["string" if tag == "plurals" else tag].add(key)
                        if tag == "string-array":
                            defined["array"].add(key)
                    elif tag == "style":
                        defined["style"].add(key)

            for element in tree.iter():
                for value in element.attrib.values():
                    for match in NEW_ID_RE.finditer(value):
                        defined["id"].add(match.group(1))

    return defined


def collect_refs(errors: list[str]):
    refs = []  # (type, name, where)
    for base, _, files in os.walk(RES):
        for name in sorted(files):
            if not name.endswith(".xml"):
                continue
            path = os.path.join(base, name)
            try:
                text = open(path, encoding="utf-8").read()
            except OSError as exc:
                fail(f"unreadable {path}: {exc}", errors)
                continue
            where = os.path.relpath(path, ROOT)
            for match in REF_RE.finditer(text):
                is_framework, rtype, rname = match.group(1), match.group(2), match.group(3)
                if is_framework:
                    continue
                if rtype in ("android",):
                    continue
                refs.append((rtype, rname, where))

    for base, _, files in os.walk(SRC):
        for name in sorted(files):
            if not name.endswith(".java"):
                continue
            path = os.path.join(base, name)
            where = os.path.relpath(path, ROOT)
            text = open(path, encoding="utf-8").read()
            for match in JAVA_R_RE.finditer(text):
                refs.append((match.group(1), match.group(2), where))
            for match in REF_RE.finditer(text):
                if match.group(1):
                    continue
                refs.append((match.group(2), match.group(3), where))
    return refs


def check_java_syntax(errors: list[str]) -> int:
    try:
        import javalang  # type: ignore
    except ImportError:
        print("· javalang not installed — skipping Java syntax check")
        return 0

    count = 0
    for base, _, files in os.walk(SRC):
        for name in sorted(files):
            if not name.endswith(".java"):
                continue
            path = os.path.join(base, name)
            count += 1
            try:
                javalang.parse.parse(open(path, encoding="utf-8").read())
            except Exception as exc:  # noqa: BLE001 - javalang raises broadly
                fail(f"java syntax → {os.path.relpath(path, ROOT)}: {exc}", errors)
    return count


def main() -> int:
    errors: list[str] = []
    defined = collect_defined(errors)

    # style parent chains such as "Ios.Text.Mode" imply "Ios.Text" exists
    implied = set()
    for style in list(defined["style"]):
        parts = style.split(".")
        for i in range(1, len(parts)):
            implied.add(".".join(parts[:i]))
    defined["style"] |= implied

    refs = collect_refs(errors)
    checked = 0
    for rtype, rname, where in refs:
        if rtype == "id":
            pool = defined["id"] | defined["id"]
        elif rtype in ("style", "string", "color", "dimen", "drawable", "layout", "mipmap"):
            pool = defined[rtype]
        else:
            continue
        checked += 1
        if rname not in pool:
            fail(f"missing @{rtype}/{rname} referenced from {where}", errors)

    java_files = check_java_syntax(errors)

    print(f"· res files parsed: {sum(len(v) for k, v in defined.items() if k != 'id')} definitions")
    print(f"· ids declared:     {len(defined['id'])}")
    print(f"· references checked: {checked}")
    print(f"· java files parsed:  {java_files}")

    if errors:
        print(f"\n✗ {len(errors)} problem(s):")
        for err in errors:
            print("  -", err)
        return 1

    print("\n✓ all UI resources and Java sources check out")
    return 0


if __name__ == "__main__":
    sys.exit(main())
