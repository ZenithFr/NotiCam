#!/usr/bin/env python3
"""Expose failing build diagnostics through the Checks API as well as CI logs.

Logs/artifact downloads can be unavailable to an API client; annotations preserve
actual compiler/lint errors without requiring a successful APK or test task.
"""
import re
import sys
from pathlib import Path


def main():
    path = Path(sys.argv[1])
    lines = path.read_text(errors="replace").splitlines()
    pattern = re.compile(r"(^e: |\berror:|^FAILURE:|^\* What went wrong|^> |^Caused by:|\.kt:\d|\.java:\d|\.cpp:\d|Lint found|^\s+Error:)")
    relevant = [line for line in lines if pattern.search(line)]
    message = "\n".join((relevant or lines[-60:])[:140])[-24000:]
    # Escape workflow-command data, not arbitrary shell commands from build output.
    message = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::error title=Build diagnostics ({path.name})::{message}")


if __name__ == "__main__":
    main()
