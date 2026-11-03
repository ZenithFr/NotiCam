#!/usr/bin/env python3
"""Verify real Android test execution and APK contents, independently of task exit codes."""
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def emit(level, title, message):
    message = str(message).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f'::{level} title={title}::{message}', flush=True)


def load_alignments(data):
    if data[:6] != b'\x7fELF\x02\x01':
        raise ValueError('Expected a little-endian ELF64 library')
    if struct.unpack_from('<H', data, 18)[0] != 183:
        raise ValueError('Expected AArch64 library')
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    return [struct.unpack_from('<Q', data, offset + i * size + 48)[0]
            for i in range(count) if struct.unpack_from('<I', data, offset + i * size)[0] == 1]


def main():
    reports = sorted((ROOT / 'build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    if not reports:
        raise ValueError('No real Gradle JUnit XML reports: a NO-SOURCE test task is not a passing test suite')
    totals = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, '0'))
    emit('notice', 'Actual Android JVM test results', totals)
    if totals['tests'] - totals['skipped'] < 129 or totals['failures'] or totals['errors']:
        raise ValueError(f'Incomplete/failing Android test suite: {totals}')

    lint_errors = []
    for variant in ('debug', 'release'):
        report = ROOT / f'build/reports/lint-results-{variant}.xml'
        if not report.is_file():
            raise ValueError(f'Missing {variant} lint report')
        issues = ET.parse(report).getroot().findall('issue')
        serious = [item for item in issues if item.get('severity') in ('Error', 'Fatal')]
        emit('notice', report.name, f'{len(serious)} errors; {len(issues) - len(serious)} other findings')
        for issue in serious[:20]:
            locations = ', '.join(f"{item.get('file')}:{item.get('line', '?')}" for item in issue.findall('location'))
            emit('error', issue.get('id', 'lint'), issue.get('message', '') + '\n' + locations)
        lint_errors.extend(serious)
        if variant == 'debug':
            nonfatal = [item for item in issues if item not in serious]
            summary = '\n'.join(f"{item.get('id')}: {item.get('message')}" for item in nonfatal)
            if summary:
                emit('notice', 'Nonfatal lint findings for review', summary[:30000])

    shaders = [f'assets/shaders/{p.stem}.spv' for p in (ROOT / 'jni/shaders').glob('*.comp')]
    for variant in ('debug', 'release'):
        apks = sorted((ROOT / 'build/outputs/apk' / variant).glob('*.apk'))
        if not apks:
            raise ValueError(f'No {variant} APK produced')
        for apk in apks:
            with zipfile.ZipFile(apk) as archive:
                names = set(archive.namelist())
                required = set(shaders + ['classes.dex', 'AndroidManifest.xml',
                    'lib/arm64-v8a/libauxcam_engine.so', 'lib/arm64-v8a/libc++_shared.so',
                    'lib/arm64-v8a/libtensorflowlite_jni.so'])
                missing = required - names
                if missing:
                    raise ValueError(f'{apk.name}: missing {sorted(missing)}')
                for name in shaders:
                    if archive.read(name)[:4] != b'\x03\x02\x23\x07':
                        raise ValueError(f'{apk.name}: invalid SPIR-V header: {name}')
                libraries = sorted(n for n in names if n.startswith('lib/') and n.endswith('.so'))
                for name in libraries:
                    if not name.startswith('lib/arm64-v8a/'):
                        raise ValueError(f'{apk.name}: unexpected ABI: {name}')
                    alignments = load_alignments(archive.read(name))
                    if not alignments or min(alignments) < 16384:
                        emit('warning', '16 KB ELF compatibility', f'{apk.name}: {name}: PT_LOAD alignments {alignments}; needs a 16 KB-compatible rebuild for those devices')
                emit('notice', 'APK contents verified', f'{apk.name}: {len(shaders)} compiled shaders; {len(libraries)} arm64 libraries; classes.dex and manifest present')
    if lint_errors:
        raise ValueError(f'Android lint contains {len(lint_errors)} errors across variants')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        emit('error', 'Android output audit failed', error)
        raise
