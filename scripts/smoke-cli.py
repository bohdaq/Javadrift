#!/usr/bin/env python3
"""Check the packaged CLI with native paths, reports and a real Git history."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
from urllib.parse import unquote

PROJECT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jar', type=Path, default=PROJECT / 'javadrift-cli/target/javadrift.jar')
parser.add_argument('--java', default='java')
parser.add_argument('--git', default='git')
parser.add_argument('--output', type=Path, default=Path(tempfile.gettempdir()) / 'javadrift-cli-results/report.json')
args = parser.parse_args()
output = args.output.resolve()
output.parent.mkdir(parents=True, exist_ok=True)
logs = output.parent / (output.stem + '-logs')
logs.mkdir(exist_ok=True)
report = {'schemaVersion': 1, 'os': platform.system(), 'runs': [], 'passed': False}
document = 'docs/user guide.md'
stale = {('JD002', 'demo.Greeter#missing'), ('JD007', '../assets/logo%20sample.txt')}
removed = {('JD004', 'demo.Greeter#greet')}


def run(name, command, cwd, expected=0):
    process = subprocess.run(command, cwd=cwd, text=True, encoding='utf-8', errors='replace',
                             capture_output=True, timeout=60)
    (logs / (name + '.log')).write_text(process.stdout + process.stderr, encoding='utf-8')
    report['runs'].append({'name': name, 'exitCode': process.returncode, 'expectedExitCode': expected})
    if process.returncode != expected:
        raise RuntimeError(f'{name}: expected exit {expected}, got {process.returncode}; see {logs / (name + ".log")}')
    return process


def verify_json(result, wanted):
    actual = {(f['checkId'], f['reference']) for f in result['findings']}
    if actual != wanted or len(result['findings']) != len(wanted) or result['documents'] != 1 or result['types'] != 1:
        raise RuntimeError(f'Unexpected JSON report: {result}')
    for finding in result['findings']:
        expected_line = 3 if finding['checkId'] == 'JD007' else 1
        if finding['file'] != document or finding['line'] != expected_line or finding['column'] < 1:
            raise RuntimeError(f'Invalid document location: {finding}')


try:
    jar = args.jar.resolve()
    report['jarSha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
    report['javaVersion'] = run('java-version', [args.java, '-version'], PROJECT).stderr.strip()
    with tempfile.TemporaryDirectory(prefix='javadrift cli ') as directory:
        base = Path(directory)
        root = base / 'project files'
        working = base / 'working files'
        working.mkdir()
        tool = base / 'tool files/javadrift cli.jar'
        tool.parent.mkdir()
        shutil.copyfile(jar, tool)
        source = root / 'src/main/java/demo/Greeter.java'
        guide = root / document
        asset = root / 'assets/logo sample.txt'
        config = root / 'config files/check config.yml'
        for path in (source, guide, asset, config):
            path.parent.mkdir(parents=True, exist_ok=True)
        source.write_text('package demo; public class Greeter { public void greet() {} }\n', encoding='utf-8')
        valid = '`demo.Greeter#greet`\n\n[asset](../assets/logo%20sample.txt)\n'
        guide.write_text(valid, encoding='utf-8')
        asset.write_text('asset\n', encoding='utf-8')
        config.write_text("docs:\n  include: ['docs/user guide.md']\nbaseline: 'accepted findings.json'\n", encoding='utf-8')
        git = [args.git, '-c', 'user.name=Javadrift Test', '-c', 'user.email=test@example.com',
               '-c', 'commit.gpgsign=false', '-c', 'tag.gpgsign=false', '-c', 'core.autocrlf=false']
        run('git-init', git + ['init'], root)
        run('git-add-base', git + ['add', '.'], root)
        run('git-commit-base', git + ['commit', '-m', 'Original API and docs'], root)
        run('git-tag-base', git + ['tag', 'portability-base'], root)
        cli = [args.java, '-jar', str(tool)]
        check = cli + ['check', '--root', str(root), '--config', str(config)]

        def check_json(name, wanted, expected=0, options=(), relative=False):
            target = Path('output files/nested reports') / (name + '.json')
            actual_target = working / target
            actual_target.unlink(missing_ok=True)
            run(name, check + ['--format', 'json', '--output', str(target if relative else actual_target)] + list(options), working, expected)
            result = json.loads(actual_target.read_text(encoding='utf-8'))
            verify_json(result, wanted)
            report['runs'][-1]['findings'] = len(result['findings'])
            print(name, 'PASS', flush=True)
            return result

        def check_sarif(name, wanted, options=()):
            target = working / 'SARIF reports' / (name + '.sarif')
            target.unlink(missing_ok=True)
            run(name, check + ['--format', 'sarif', '--output', str(target)] + list(options), working, 1)
            result = json.loads(target.read_text(encoding='utf-8'))
            findings = result['runs'][0]['results']
            if result['version'] != '2.1.0' or {f['ruleId'] for f in findings} != {c for c, _ in wanted} or len(findings) != len(wanted):
                raise RuntimeError(f'Unexpected SARIF findings: {findings}')
            for finding in findings:
                location = finding['locations'][0]['physicalLocation']
                if location['artifactLocation']['uri'] != 'docs/user%20guide.md' or unquote(location['artifactLocation']['uri']) != document:
                    raise RuntimeError(f'Invalid SARIF path: {location}')
                if location['region']['startLine'] != (3 if finding['ruleId'] == 'JD007' else 1) or location['region']['startColumn'] < 1:
                    raise RuntimeError(f'Invalid SARIF position: {location}')
            print(name, 'PASS', flush=True)
            return findings

        check_json('valid-absolute-output', set())
        verify_json(json.loads(run('valid-stdout', check + ['--format', 'json'], working).stdout), set())
        guide.write_text(valid.replace('#greet', '#missing'), encoding='utf-8')
        asset.unlink()
        check_json('stale-relative-output', stale, 1, relative=True)
        check_json('stale-warn-only', stale, options=['--warn-only'])
        check_sarif('stale-sarif', stale)
        text = run('stale-text', check, working, 1).stdout
        if 'JD002' not in text or 'JD007' not in text or document + ':1:' not in text or document + ':3:' not in text:
            raise RuntimeError(f'Unexpected text report: {text}')
        annotations = run('stale-github', check + ['--format', 'github'], working, 1).stdout
        if len(annotations.splitlines()) != 2 or any('file=' + document + ',line=' not in line for line in annotations.splitlines()):
            raise RuntimeError(f'Unexpected GitHub annotations: {annotations}')
        run('missing-config', cli + ['check', '--root', str(root), '--config', str(root / 'missing config.yml')], working, 2)
        run('missing-config-warn-only', cli + ['check', '--root', str(root), '--config', str(root / 'missing config.yml'), '--warn-only'], working, 2)
        run('invalid-output', check + ['--output', str(working)], working, 2)

        guide.write_text(valid, encoding='utf-8')
        asset.write_text('asset\n', encoding='utf-8')
        source.write_text('package demo; public class Greeter { public void welcome() {} }\n', encoding='utf-8')
        run('git-add-change', git + ['add', '.'], root)
        run('git-commit-change', git + ['commit', '-m', 'Rename greet to welcome'], root)
        history = ['--since', 'portability-base']
        check_json('history-strict', removed, 1, history)
        check_json('history-warn-only', removed, options=history + ['--warn-only'])
        sarif = check_sarif('history-sarif', removed, history)
        fingerprint = hashlib.sha256((document + '\0JD004\0demo.Greeter#greet').encode('utf-8')).hexdigest()
        if sarif[0]['partialFingerprints']['javadrift/v1'] != fingerprint:
            raise RuntimeError('SARIF fingerprint is not independent of native path separators')
        run('invalid-git-ref', check + ['--since', 'missing-portability-ref'], working, 2)
        run('invalid-git-ref-warn-only', check + ['--since', 'missing-portability-ref', '--warn-only'], working, 2)
        baseline = root / 'accepted findings.json'
        run('create-baseline', cli + ['baseline', '--root', str(root), '--config', str(config), '--since', 'portability-base', '--output', str(baseline)], working)
        if json.loads(baseline.read_text(encoding='utf-8')) != {'version': 1, 'fingerprints': [fingerprint]}:
            raise RuntimeError('Unexpected baseline fingerprint')
        check_json('history-baseline', set(), options=history)
        maintenance = cli + ['baseline', '--root', str(root), '--config', str(config)] + history
        original = baseline.read_bytes()
        run('baseline-audit-active', maintenance + ['--check'], working)
        if baseline.read_bytes() != original:
            raise RuntimeError('Baseline audit changed the file')
        guide.write_text(valid.replace('#greet', '#missing'), encoding='utf-8')
        new_finding = {('JD002', 'demo.Greeter#missing')}
        audit = run('baseline-audit-unused', maintenance + ['--check'], working, 1)
        if '1 unused' not in audit.stdout or '1 current fingerprints remain unaccepted' not in audit.stdout or baseline.read_bytes() != original:
            raise RuntimeError('Unused-entry audit must retain the baseline and distinguish new findings')
        run('baseline-prune', maintenance + ['--prune'], working)
        if json.loads(baseline.read_text(encoding='utf-8')) != {'version': 1, 'fingerprints': []}:
            raise RuntimeError('Pruning accepted a new finding')
        check_json('baseline-new-finding-retained', new_finding, 1, history)
        pruned = baseline.read_bytes()
        run('baseline-conflicting-options', maintenance + ['--check', '--prune'], working, 2)
        run('baseline-missing-classpath', maintenance + ['--prune', '--classpath', str(base / 'missing.jar')], working, 2)
        if baseline.read_bytes() != pruned:
            raise RuntimeError('Input failures changed the baseline')
        guide.write_text(valid.replace('#greet', '#welcome'), encoding='utf-8')
        check_json('history-restored', set(), options=history)
        run('baseline-audit-empty', maintenance + ['--check'], working)
        run('baseline-prune-noop', maintenance + ['--prune'], working)
        if baseline.read_bytes() != pruned:
            raise RuntimeError('No-op prune rewrote the baseline')
    report['cliScenarios'] = sum(r['name'] != 'java-version' and not r['name'].startswith('git-') for r in report['runs'])
    report['passed'] = True
except Exception as error:
    report['error'] = f'{type(error).__name__}: {error}'
    print(report['error'], flush=True)
finally:
    output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('Report:', output, flush=True)
if not report['passed']:
    raise SystemExit(1)
