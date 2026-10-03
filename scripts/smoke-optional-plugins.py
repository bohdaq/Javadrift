#!/usr/bin/env python3
"""Check optional rules through source-built Maven and Gradle adapters."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--maven', default='mvn')
parser.add_argument('--skip-build', action='store_true', help='Use an already locally installed Maven plugin')
parser.add_argument('--output', type=Path, default=Path(tempfile.gettempdir()) / 'javadrift-optional-plugins/report.json')
args = parser.parse_args()
output = args.output.resolve()
output.parent.mkdir(parents=True, exist_ok=True)
logs = output.parent / (output.stem + '-logs')
logs.mkdir(exist_ok=True)
maven = shutil.which(args.maven) or args.maven
wrapper = PROJECT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
report = {'schemaVersion': 1, 'scope': 'Authored Jackson application; locally built adapters', 'runs': [], 'passed': False}


def identity(finding):
    return tuple(finding[k] for k in ('checkId', 'file', 'line', 'column', 'reference'))


def run(name, command, root, expected=0):
    process = subprocess.run(command, cwd=root, text=True, encoding='utf-8', errors='replace',
                             capture_output=True, timeout=600)
    text = process.stdout + process.stderr
    (logs / (name + '.log')).write_text(text, encoding='utf-8')
    report['runs'].append({'name': name, 'exitCode': process.returncode, 'expectedExitCode': expected})
    if process.returncode != expected:
        raise RuntimeError(f'{name}: expected exit {expected}, got {process.returncode}; see {logs / (name + ".log")}')
    return text


try:
    triage_path = PROJECT / 'validation/optional-checks-triage.json'
    triage = json.loads(triage_path.read_text(encoding='utf-8'))
    reviewed = triage['findings']
    if len(reviewed) != 3 or len({identity(f) for f in reviewed}) != 3 or any(
            f['classification'] != 'true-positive' or not f['evidence'] for f in reviewed):
        raise RuntimeError('Expected three unique reviewed true positives')
    report['triageSha256'] = hashlib.sha256(triage_path.read_bytes()).hexdigest()
    if not args.skip_build:
        run('install-maven-adapter', [maven, '-B', '-DskipTests', '-pl', 'javadrift-maven-plugin', '-am', 'install'], PROJECT)
    with tempfile.TemporaryDirectory(prefix='javadrift optional plugins ') as directory:
        for adapter in ('maven', 'gradle'):
            root = Path(directory) / (adapter + ' application')
            shutil.copytree(PROJECT / triage['fixture'], root, ignore=shutil.ignore_patterns('target', 'build', '.gradle'))
            for name, digest in triage['fixtureHashes'].items():
                if hashlib.sha256((root / 'variants' / name).read_text(encoding='utf-8').encode('utf-8')).hexdigest() != digest:
                    raise RuntimeError(f'Review changed fixture: {name}')
            configuration = root / 'javadrift.yml'
            enabled = configuration.read_text(encoding='utf-8')
            target = root / ('target/javadrift.json' if adapter == 'maven' else 'build/reports/javadrift/report.txt')
            command = ([maven, '-B', '-Pjavadrift', 'verify'] if adapter == 'maven' else
                       [str(wrapper), '-p', str(root), '-PjavadriftSource=' + str(PROJECT), 'check', '--no-daemon', '--console=plain'])

            def select(guide='valid', settings='valid'):
                shutil.copyfile(root / f'variants/{guide}-guide.md', root / 'docs/guide.md')
                shutil.copyfile(root / f'variants/{settings}-settings.adoc', root / 'docs/settings.adoc')

            def check(name, wanted=(), expected=0, warn=False, severity='ERROR', outcome=None, input_error=False):
                # Preserve Gradle's output to exercise real up-to-date behavior.
                if adapter == 'maven':
                    target.unlink(missing_ok=True)
                prior = target.read_bytes() if target.exists() else None
                suffix = ['-Djavadrift.warnOnly=true'] if adapter == 'maven' else ['-PwarnOnly=true']
                text = run(adapter + '-' + name, command + (suffix if warn else []), root, expected)
                if adapter == 'gradle':
                    match = re.search(r'^> Task :javadriftCheck(?: (UP-TO-DATE|FAILED|FROM-CACHE|SKIPPED))?\s*$', text, re.M)
                    actual = (match.group(1) or 'EXECUTED') if match else None
                    wanted_outcome = outcome or ('FAILED' if expected else 'EXECUTED')
                    allowed = (wanted_outcome,) if isinstance(wanted_outcome, str) else wanted_outcome
                    if actual not in allowed:
                        raise RuntimeError(f'{name}: expected task {wanted_outcome}, got {actual}')
                    report['runs'][-1]['taskOutcome'] = actual
                if input_error:
                    if 'Javadrift:' not in text or 'report: [unclosed' not in text:
                        raise RuntimeError(f'{name}: missing resource input diagnostic')
                    if (target.read_bytes() if target.exists() else None) != prior:
                        raise RuntimeError(f'{name}: input failure replaced the last successful report')
                else:
                    result = json.loads(target.read_text(encoding='utf-8'))
                    if result['documents'] != 2 or result['types'] != 1 or len(result['findings']) != len(wanted) or \
                            {identity(f) for f in result['findings']} != {identity(f) for f in wanted}:
                        raise RuntimeError(f'{name}: unexpected or missing reviewed finding: {result}')
                    if any(f['severity'] != severity for f in result['findings']):
                        raise RuntimeError(f'{name}: wrong severity')
                    (logs / (adapter + '-' + name + '.json')).write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
                    report['runs'][-1]['findings'] = len(result['findings'])
                print(adapter + '-' + name, 'PASS', flush=True)

            snippets = [f for f in reviewed if f['checkId'] == 'JD008']
            properties = [f for f in reviewed if f['checkId'] == 'JD009']
            check('valid')
            classes = root / ('target/classes' if adapter == 'maven' else 'build/classes/java/main')
            if not (classes / 'demo/ReportService.class').is_file():
                raise RuntimeError('Application class was not compiled')
            if adapter == 'gradle':
                check('unchanged', outcome='UP-TO-DATE')
            select(guide='stale')
            check('snippet-stale', snippets, 1)
            select(settings='stale')
            check('properties-stale', properties, 1)
            select('stale', 'stale')
            check('both-stale', reviewed, 1)
            check('warn-only', reviewed, warn=True)
            configuration.write_text(enabled.replace(': error', ': off'), encoding='utf-8')
            check('disabled')
            configuration.write_text(enabled.replace(': error', ': warning'), encoding='utf-8')
            check('warnings', reviewed, severity='WARNING')
            configuration.write_text(enabled.replace(': error', ': warning') + '\nfailOn: warning\n', encoding='utf-8')
            check('warning-fails', reviewed, 1, severity='WARNING')
            configuration.write_text(enabled, encoding='utf-8')
            select()
            check('restored')
            if adapter == 'gradle':
                resource = root / 'src/main/resources/application.properties'
                original = resource.read_text(encoding='utf-8')
                resource.write_text('', encoding='utf-8')
                finding = {'checkId': 'JD009', 'file': 'docs/settings.adoc', 'line': 7, 'column': 1, 'reference': 'report.batch-size'}
                check('resource-removed', [finding], 1)
                resource.write_text(original, encoding='utf-8')
                check('resource-restored')
                check('restored-unchanged', outcome='UP-TO-DATE')
            malformed = root / 'src/main/resources/malformed.yml'
            malformed.write_text('report: [unclosed\n', encoding='utf-8')
            check('input-error-warn-only', expected=1, warn=True, input_error=True)
            malformed.unlink()
            # Restoring the last successful inputs can reuse its preserved report.
            check('final-valid', outcome=('EXECUTED', 'UP-TO-DATE') if adapter == 'gradle' else None)
    report['passed'] = True
except Exception as error:
    report['error'] = f'{type(error).__name__}: {error}'
    print(report['error'], flush=True)
finally:
    output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('Report:', output, flush=True)
if not report['passed']:
    raise SystemExit(1)
