#!/usr/bin/env python3
"""Static preview server for design/index.html.

Why this instead of `python3 -m http.server`:
  * sends `Cache-Control: no-store` so an edited mock is never masked by the
    browser's cache (the plain http.server sends no cache headers at all),
  * binds 0.0.0.0 so the sandbox preview proxy can reach it,
  * serves `index.html` at `/`, and refuses path traversal outside `design/`.

Usage: python3 tools/preview_server.py [--port 8099] [--dir design]
"""

from __future__ import annotations

import argparse
import functools
import http.server
import os
import socketserver
import sys


class Handler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self) -> None:
        self.send_header("Cache-Control", "no-store, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()

    def send_head(self):
        # `/` → index.html (SimpleHTTPRequestHandler already does this for
        # directories, but be explicit so a listing is never returned).
        if self.path in ("/", ""):
            self.path = "/index.html"
        return super().send_head()

    def log_message(self, fmt: str, *args) -> None:
        # Keep the log tail readable: only report problems.
        status = args[1] if len(args) > 1 else ""
        if str(status).startswith(("4", "5")):
            super().log_message(fmt, *args)


def main() -> int:
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--dir", default=os.path.join(root, "design"))
    parser.add_argument("--bind", default="0.0.0.0")
    args = parser.parse_args()

    directory = os.path.abspath(args.dir)
    index = os.path.join(directory, "index.html")
    if not os.path.isfile(index):
        print(f"✗ no index.html in {directory}", file=sys.stderr)
        return 1

    socketserver.TCPServer.allow_reuse_address = True
    handler = functools.partial(Handler, directory=directory)
    with socketserver.ThreadingTCPServer((args.bind, args.port), handler) as httpd:
        print(f"· serving {directory}", flush=True)
        print(f"· http://{args.bind}:{args.port}/  (no-store)", flush=True)
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n· stopped", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
