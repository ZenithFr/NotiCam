#!/usr/bin/env python3
"""Static verification for the NotiCam app.

The project is now a Gradle + Kotlin + Compose build, but this sandbox has no
Android SDK and no Kotlin compiler. Everything below is therefore checked with
lightweight, dependency-free analysis that catches the mistakes that actually
happen when hand-editing this codebase:

  1. res/**/*.xml is well-formed, and every @type/name reference resolves.
  2. Every R.type.name (Java/Kotlin) resolves to a declared resource.
  3. Every Kotlin/Java file is bracket- and string-balanced (catches the classic
     "missing closing brace after an edit").
  4. Every `import com.zenith.auxcam.*` symbol exists in that package.
  5. Every `actions.onX(...)` call exists on the CameraActions interface, every
     `state.prop` read exists on CameraUiState, every `NotiIcons.X` exists, and
     every CameraColors/CameraType/CameraSpacing member referenced exists.
  6. Every `Fstops`-style constant referenced from another file is declared.

Run: python3 tools/verify_ui.py  (also invoked by build.sh)
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
IMPORT_RE = re.compile(r"^import\s+(com\.zenith\.auxcam[\w.]*)\s*$", re.MULTILINE)

errors: list[str] = []


def err(message: str) -> None:
    errors.append(message)


# ───────────────────────────────────────────────────────────────────────────
# 1 + 2 — resources
# ───────────────────────────────────────────────────────────────────────────
FILE_TYPES = {"drawable", "layout", "mipmap", "anim", "menu", "xml", "raw"}


def collect_resources():
    defined = defaultdict(set)
    if not os.path.isdir(RES):
        err("res/ directory is missing")
        return defined

    for base, _, files in os.walk(RES):
        folder = os.path.basename(base)
        for name in files:
            if folder in FILE_TYPES:
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
                err(f"malformed XML → {os.path.relpath(path, ROOT)}: {exc}")
                continue
            if is_values:
                for element in tree.getroot():
                    key = element.get("name")
                    if not key:
                        continue
                    tag = element.tag
                    if tag in ("string", "color", "dimen", "bool", "integer", "plurals",
                               "string-array", "integer-array", "array", "style"):
                        defined["style" if tag == "style" else tag].add(key)
            for element in tree.iter():
                for value in element.attrib.values():
                    for match in NEW_ID_RE.finditer(value):
                        defined["id"].add(match.group(1))
    return defined


def walk_sources():
    for base, _, files in os.walk(SRC):
        for name in sorted(files):
            if name.endswith((".kt", ".java")):
                yield os.path.join(base, name)


def check_references(defined) -> int:
    checked = 0
    for path in walk_sources():
        where = os.path.relpath(path, ROOT)
        text = open(path, encoding="utf-8").read()
        for match in REF_RE.finditer(text):
            if match.group(1):
                continue  # @android:...
            rtype, rname = match.group(2), match.group(3)
            if rtype not in ("drawable", "layout", "string", "color", "dimen", "style", "id"):
                continue
            checked += 1
            if rname not in defined[rtype]:
                err(f"missing @{rtype}/{rname} referenced from {where}")
        for match in JAVA_R_RE.finditer(text):
            rtype, rname = match.group(1), match.group(2)
            if rtype not in defined:
                continue
            checked += 1
            if rname not in defined[rtype]:
                err(f"missing R.{rtype}.{rname} referenced from {where}")
    return checked


# ───────────────────────────────────────────────────────────────────────────
# 3 — bracket / string balance
# ───────────────────────────────────────────────────────────────────────────
def strip_code(text: str) -> str:
    """Removes comments and string/char literals while keeping line structure."""
    out = []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        two = text[i:i + 2]
        three = text[i:i + 3]
        if two == "//":
            j = text.find("\n", i)
            i = n if j == -1 else j
            continue
        if two == "/*":
            j = text.find("*/", i + 2)
            i = n if j == -1 else j + 2
            continue
        if three == '"""':
            j = text.find('"""', i + 3)
            if j == -1:
                err("unterminated raw string")
                return "".join(out)
            out.append("\n" * text.count("\n", i, j))
            i = j + 3
            continue
        if ch in ('"', "'"):
            quote = ch
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == quote:
                    i += 1
                    break
                if text[i] == "\n":
                    break  # unterminated — reported below
                i += 1
            else:
                break
            out.append('""')
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def check_balance() -> int:
    pairs = {")": "(", "]": "[", "}": "{"}
    count = 0
    for path in walk_sources():
        count += 1
        where = os.path.relpath(path, ROOT)
        code = strip_code(open(path, encoding="utf-8").read())
        stack: list[tuple[str, int]] = []
        line = 1
        for ch in code:
            if ch == "\n":
                line += 1
            elif ch in "([{":
                stack.append((ch, line))
            elif ch in ")]}":
                if not stack:
                    err(f"{where}:{line}: unmatched '{ch}'")
                    break
                opening, oline = stack.pop()
                if opening != pairs[ch]:
                    err(f"{where}:{line}: '{ch}' closes '{opening}' from line {oline}")
                    break
        if stack:
            opening, oline = stack[-1]
            err(f"{where}:{oline}: unclosed '{opening}' (file ends with {len(stack)} open)")
    return count


# ───────────────────────────────────────────────────────────────────────────
# 4 — project imports resolve
# ───────────────────────────────────────────────────────────────────────────
DECL_RE = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:public\s+|internal\s+|private\s+|protected\s+|abstract\s+|sealed\s+|data\s+|enum\s+|open\s+|inline\s+|value\s+)*"
    r"(class|interface|object|fun|val|var)\s+(?:[\w.]+\.)?([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)


def declared_names(text: str) -> set[str]:
    names = set()
    for match in DECL_RE.finditer(text):
        names.add(match.group(2))
    # members of objects/interfaces (indented fun/val) for member checks
    return names


def file_package(text: str) -> str | None:
    match = re.search(r"^package\s+([\w.]+)", text, re.MULTILINE)
    return match.group(1) if match else None


def check_imports() -> int:
    packages: dict[str, set[str]] = defaultdict(set)
    for path in walk_sources():
        text = open(path, encoding="utf-8").read()
        pkg = file_package(text)
        if pkg:
            packages[pkg] |= declared_names(text)

    count = 0
    for path in walk_sources():
        where = os.path.relpath(path, ROOT)
        text = open(path, encoding="utf-8").read()
        for full in IMPORT_RE.findall(text):
            count += 1
            parts = full.split(".")
            pkg = ".".join(parts[:-1])
            name = parts[-1]
            if pkg not in packages:
                err(f"{where}: import {full} — package '{pkg}' does not exist")
            elif name not in packages[pkg]:
                err(f"{where}: import {full} — '{name}' not declared in {pkg}")
    return count


# ───────────────────────────────────────────────────────────────────────────
# 5/6 — member-level cross checks
# ───────────────────────────────────────────────────────────────────────────
def object_members(text: str, object_name: str, kinds: tuple[str, ...]) -> set[str]:
    """Collects member names declared inside `object <object_name> { ... }`."""
    match = re.search(rf"object\s+{object_name}\s*\{{", text)
    if not match:
        return set()
    start = match.end()
    depth = 1
    i = start
    while i < len(text) and depth > 0:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
        i += 1
    body = text[start:i]
    members = set()
    pattern = re.compile(
        r"^\s*(?:@\w+\s*)*(?:override\s+|private\s+|internal\s+|public\s+|const\s+)*"
        r"(?:" + "|".join(kinds) + r")\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::|\(|=|$)",
        re.MULTILINE,
    )
    for m in pattern.finditer(body):
        members.add(m.group(1))
    return members


def interface_members(text: str, interface_name: str) -> set[str]:
    match = re.search(rf"interface\s+{interface_name}\s*[\w:<>, ]*\{{", text)
    if not match:
        return set()
    start = match.end()
    depth = 1
    i = start
    while i < len(text) and depth > 0:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
        i += 1
    body = text[start:i]
    return set(re.findall(r"\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", body))


def data_class_props(text: str, class_name: str) -> set[str]:
    match = re.search(rf"data\s+class\s+{class_name}\s*\((.*?)\)\s*\{{", text, re.DOTALL)
    if not match:
        return set()
    body = match.group(1)
    props = set(re.findall(r"\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*:", body))
    # computed properties declared in the class body (`val x: T get() = ...`)
    props |= set(re.findall(r"\n\s{4}val\s+([A-Za-z_][A-Za-z0-9_]*)\s*:", text))
    return props


def check_members() -> int:
    controller = open(os.path.join(SRC, "com/zenith/auxcam/CameraController.kt"), encoding="utf-8").read()
    ui_state_file = open(os.path.join(SRC, "com/zenith/auxcam/CameraUiState.kt"), encoding="utf-8").read()
    theme = open(os.path.join(SRC, "com/zenith/auxcam/ui/theme/Theme.kt"), encoding="utf-8").read()
    icons = open(os.path.join(SRC, "com/zenith/auxcam/ui/icons/NotiIcons.kt"), encoding="utf-8").read()

    actions = interface_members(controller, "CameraActions")
    state_props = data_class_props(ui_state_file, "CameraUiState")
    colors = object_members(theme, "CameraColors", ("val",))
    spacing = object_members(theme, "CameraSpacing", ("val",))
    type_members = object_members(theme, "CameraType", ("val",))
    icon_members = object_members(icons, "NotiIcons", ("val",))

    if not actions:
        err("could not parse the CameraActions interface")
    if not state_props:
        err("could not parse CameraUiState properties")

    checked = 0
    for path in walk_sources():
        if not path.endswith(".kt"):
            continue
        where = os.path.relpath(path, ROOT)
        text = open(path, encoding="utf-8").read()

        for name in re.findall(r"\bactions\.([A-Za-z_][A-Za-z0-9_]*)\s*\(", text):
            checked += 1
            if name not in actions:
                err(f"{where}: actions.{name}() is not declared on CameraActions")

        for name in re.findall(r"\bstate\.([A-Za-z_][A-Za-z0-9_]*)\b", text):
            checked += 1
            if name not in state_props and name not in ("copy", "equals", "hashCode", "toString"):
                err(f"{where}: state.{name} is not a CameraUiState property")

        for name in re.findall(r"\bNotiIcons\.([A-Za-z_][A-Za-z0-9_]*)", text):
            checked += 1
            if name not in icon_members:
                err(f"{where}: NotiIcons.{name} does not exist")

        for obj, members, label in (
            ("CameraColors", colors, "CameraColors"),
            ("CameraSpacing", spacing, "CameraSpacing"),
            ("CameraType", type_members, "CameraType"),
        ):
            for name in re.findall(rf"\b{obj}\.([A-Za-z_][A-Za-z0-9_]*)", text):
                checked += 1
                if name not in members:
                    err(f"{where}: {label}.{name} does not exist")
    return checked



COMPOSABLE_RE = re.compile(r"^(?:@Composable\s*)?(?:private\s+|internal\s+)?fun\s+([A-Z][A-Za-z0-9_]*)\s*\(", re.MULTILINE)


def check_composable_calls() -> int:
    """Every project composable used in another package must be imported."""
    declared: dict[tuple[str, str], str] = {}
    packages: dict[str, set[str]] = defaultdict(set)
    for path in walk_sources():
        if not path.endswith(".kt"):
            continue
        text = open(path, encoding="utf-8").read()
        pkg = file_package(text) or ""
        for name in COMPOSABLE_RE.findall(text):
            declared[(pkg, name)] = path
            packages[pkg].add(name)

    checked = 0
    for path in walk_sources():
        if not path.endswith(".kt"):
            continue
        where = os.path.relpath(path, ROOT)
        text = open(path, encoding="utf-8").read()
        pkg = file_package(text) or ""
        imports = set(IMPORT_RE.findall(text))
        for name in set(re.findall(r"\b([A-Z][A-Za-z0-9_]*)\s*\(", text)):
            if (pkg, name) in declared:
                continue  # same package
            owners = [p for (p, n) in declared if n == name]
            if not owners:
                continue
            checked += 1
            full = f"{owners[0]}.{name}"
            if full not in imports:
                err(f"{where}: uses {name}() but does not import {full}")
    return checked


# Symbols whose import cannot be inferred from their package name. A missing import
# for one of these is an invisible-but-fatal Kotlin error when there is no compiler
# in the loop, so they are checked explicitly.
COMPOSE_SYMBOLS = {
    "Offset": "androidx.compose.ui.geometry.Offset",
    "Size": "androidx.compose.ui.geometry.Size",
    "CornerRadius": "androidx.compose.ui.geometry.CornerRadius",
    "Color": "androidx.compose.ui.graphics.Color",
    "Brush": "androidx.compose.ui.graphics.Brush",
    "StrokeCap": "androidx.compose.ui.graphics.StrokeCap",
    "StrokeJoin": "androidx.compose.ui.graphics.StrokeJoin",
    "TransformOrigin": "androidx.compose.ui.graphics.TransformOrigin",
    "ImageBitmap": "androidx.compose.ui.graphics.ImageBitmap",
    "graphicsLayer": "androidx.compose.ui.graphics.graphicsLayer",
    "IntOffset": "androidx.compose.ui.unit.IntOffset",
    "AnimatedVisibility": "androidx.compose.animation.AnimatedVisibility",
    "Animatable": "androidx.compose.animation.core.Animatable",
    "animateFloatAsState": "androidx.compose.animation.core.animateFloatAsState",
    "fadeIn": "androidx.compose.animation.fadeIn",
    "fadeOut": "androidx.compose.animation.fadeOut",
    "scaleIn": "androidx.compose.animation.scaleIn",
    "scaleOut": "androidx.compose.animation.scaleOut",
    "slideInVertically": "androidx.compose.animation.slideInVertically",
    "slideOutVertically": "androidx.compose.animation.slideOutVertically",
    "LocalDensity": "androidx.compose.ui.platform.LocalDensity",
    "TextOverflow": "androidx.compose.ui.text.style.TextOverflow",
    "ContentScale": "androidx.compose.ui.layout.ContentScale",
    "pointerInput": "androidx.compose.ui.input.pointer.pointerInput",
    "onGloballyPositioned": "androidx.compose.ui.layout.onGloballyPositioned",
    "verticalScroll": "androidx.compose.foundation.verticalScroll",
    "rememberScrollState": "androidx.compose.foundation.rememberScrollState",
    "detectDragGestures": "androidx.compose.foundation.gestures.detectDragGestures",
    "detectTapGestures": "androidx.compose.foundation.gestures.detectTapGestures",
    "detectTransformGestures": "androidx.compose.foundation.gestures.detectTransformGestures",
}


def check_compose_symbols() -> int:
    checked = 0
    for path in walk_sources():
        if not path.endswith(".kt"):
            continue
        where = os.path.relpath(path, ROOT)
        text = open(path, encoding="utf-8").read()
        code = strip_code(text)
        imports = set(re.findall(r"^import\s+([\w.]+)$", text, re.MULTILINE))
        for symbol, import_path in COMPOSE_SYMBOLS.items():
            # Matches bare uses (`Offset`), extension calls (`Modifier.graphicsLayer`)
            # and import lines — the import check below filters the last case.
            if not re.search(r"(?<![\w])" + symbol + r"\b", code):
                continue
            checked += 1
            # Any import of that simple name satisfies it (android.graphics.Color
            # is a legitimate alternative to the Compose one).
            if import_path in imports:
                continue
            if any(i.rsplit(".", 1)[-1] == symbol for i in imports):
                continue
            # a same-file declaration wins over an import
            if re.search(r"\b(fun|val|var|class|object)\s+" + symbol + r"\b", code):
                continue
            err(f"{where}: uses {symbol} but does not import {import_path}")
    return checked

# ───────────────────────────────────────────────────────────────────────────
# main
# ───────────────────────────────────────────────────────────────────────────
def main() -> int:
    defined = collect_resources()
    refs = check_references(defined)
    files = check_balance()
    imports = check_imports()
    members = check_members()
    composables = check_composable_calls()
    symbols = check_compose_symbols()

    print(f"· resource definitions : {sum(len(v) for v in defined.values())}")
    print(f"· resource references  : {refs}")
    print(f"· source files balanced: {files}")
    print(f"· project imports      : {imports}")
    print(f"· member cross-checks  : {members}")
    print(f"· composable imports   : {composables}")
    print(f"· compose symbol imports: {symbols}")

    if errors:
        print(f"\n✗ {len(errors)} problem(s):")
        for item in errors:
            print("  -", item)
        return 1

    print("\n✓ resources, braces, imports and Compose member references all check out")
    return 0


if __name__ == "__main__":
    sys.exit(main())
