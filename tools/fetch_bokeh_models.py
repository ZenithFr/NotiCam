#!/usr/bin/env python3
"""Fetch pinned bokeh weights for APK bundling. Never accepts an unchecked download.

Uses public HTTPS endpoints only; no credentials. Existing valid files work offline.
--verify only checks local assets and never accesses the network.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'assets/models/bokeh-models.json'


def valid(path, spec):
    return (path.is_file() and path.stat().st_size == spec['size']
            and hashlib.sha256(path.read_bytes()).hexdigest() == spec['sha256'])


def fetch(spec):
    failures = []
    for url in spec.get('urls', []):
        try:
            request = urllib.request.Request(url, headers={'User-Agent': 'NotiCam-model-fetch/1',
                                                          'Accept': 'application/vnd.github+json'})
            with urllib.request.urlopen(request, timeout=120) as response:
                # Enough for the largest pinned base64 GitHub blob, never unbounded.
                payload = response.read(spec['size'] * 2 + 4096)
            if '/git/blobs/' in url:
                blob = json.loads(payload)
                if blob.get('encoding') != 'base64':
                    raise ValueError('Expected a base64 git blob, not an LFS pointer')
                payload = base64.b64decode(blob['content'])
            if len(payload) != spec['size'] or hashlib.sha256(payload).hexdigest() != spec['sha256']:
                raise ValueError('Size or SHA-256 mismatch')
            return payload
        except Exception as error:
            # Do not print signed redirect URLs or any authentication data.
            failures.append(type(error).__name__)
    raise RuntimeError(f"Cannot fetch verified {spec['asset']} ({', '.join(failures)}). "
                       'Restore network access or supply the matching file; no model is substituted.')


def prune_retired_assets(root):
    # This exact path was a generated APK weight, never a user's camera media.
    # Do not recursively clean optional/: unrelated user-supplied files survive.
    (root / 'assets/models/optional/rvm_mobilenetv3_fp32.onnx').unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    for spec in json.loads(MANIFEST.read_text())['files']:
        path = ROOT / 'assets' / spec['asset']
        if not valid(path, spec):
            if args.verify or not spec.get('urls'):
                raise SystemExit(f"Missing/corrupt model: {spec['asset']}")
            payload = fetch(spec)
            path.parent.mkdir(parents=True, exist_ok=True)
            # Same directory for atomic replacement. optional/ is wholly ignored.
            with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temporary:
                temporary.write(payload)
                temporary_path = Path(temporary.name)
            temporary_path.replace(path)
        print(f"Verified {spec['asset']} ({spec['size']} bytes)")
    if not args.verify:
        prune_retired_assets(ROOT)


if __name__ == '__main__':
    main()
