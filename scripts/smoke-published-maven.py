#!/usr/bin/env python3
"""Verify the Maven Central plugin from an isolated consumer and empty local repository."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--maven', default='mvn')
parser.add_argument('--output', type=Path, default=Path(tempfile.gettempdir()) / 'javadrift-published-maven/report.json')
args = parser.parse_args()
output = args.output.resolve()
output.parent.mkdir(parents=True, exist_ok=True)
logs = output.parent / (output.stem + '-logs')
logs.mkdir(exist_ok=True)
summary = {'schemaVersion': 1, 'version': '0.3.1', 'scope': 'Maven Central plugin; temporary standalone consumer; initially empty local Maven repository',
           'runs': [], 'passed': False}


def run(name, options, expected, findings=None):
    report_path = consumer / 'target/javadrift.json'
    report_path.unlink(missing_ok=True)
    process = subprocess.run([args.maven, '-B', f'-Dmaven.repo.local={repository}', 'verify'] + options,
                             cwd=consumer, text=True, encoding='utf-8', errors='replace', capture_output=True, timeout=300)
    (logs / (name + '.log')).write_text(process.stdout + process.stderr, encoding='utf-8')
    row = {'name': name, 'exitCode': process.returncode, 'expectedExitCode': expected}
    summary['runs'].append(row)
    if process.returncode != expected:
        raise RuntimeError(f'{name}: expected exit {expected}, got {process.returncode}; see {logs / (name + ".log")}')
    if findings is not None:
        result = json.loads(report_path.read_text(encoding='utf-8'))
        actual = {(f['checkId'], f['reference']) for f in result['findings']}
        if actual != findings or len(result['findings']) != len(findings) or result['documents'] != 1 or result['types'] != 1:
            raise RuntimeError(f'{name}: unexpected findings: {result}')
        (logs / (name + '.json')).write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        row['findings'] = len(result['findings'])
    elif report_path.exists() or 'Configuration file does not exist' not in process.stdout + process.stderr:
        raise RuntimeError(f'{name}: expected an input failure without a stale report')
    print(name, 'PASS', flush=True)


try:
    with tempfile.TemporaryDirectory(prefix='javadrift published maven ') as directory:
        base = Path(directory)
        consumer, repository = base / 'consumer project', base / 'empty Maven repository'
        shutil.copytree(PROJECT / 'examples/published-maven', consumer, ignore=shutil.ignore_patterns('target', '.git'))
        repository.mkdir()
        if any(repository.iterdir()):
            raise RuntimeError('Consumer Maven repository must initially be empty')
        run('valid-strict', ['-Djavadrift.warnOnly=false'], 0, set())
        for artifact in ('javadrift', 'javadrift-core', 'javadrift-maven-plugin'):
            pom = repository / 'io/github/bohdaq' / artifact / '0.3.1' / f'{artifact}-0.3.1.pom'
            if not pom.is_file():
                raise RuntimeError(f'Published artifact was not resolved: {artifact}')
            tracking = (pom.parent / '_remote.repositories').read_text(encoding='utf-8')
            if f'{pom.name}>central=' not in tracking:
                raise RuntimeError(f'Artifact was not resolved from Central: {artifact}')
        summary['artifactsResolvedFromCentral'] = ['javadrift', 'javadrift-core', 'javadrift-maven-plugin']
        guide = consumer / 'docs/guide.adoc'
        valid = guide.read_bytes()
        shutil.copyfile(consumer / 'variants/stale.adoc', guide)
        stale = {('JD002', 'demo.RegistryApi#missing')}
        run('stale-warn-only', ['-Djavadrift.warnOnly=true'], 0, stale)
        run('stale-strict', ['-Djavadrift.warnOnly=false'], 1, stale)
        run('invalid-config-warn-only', ['-Djavadrift.warnOnly=true', f'-Djavadrift.config={consumer / "missing config.yml"}'], 1)
        guide.write_bytes(valid)
        run('restored-strict', ['-Djavadrift.warnOnly=false'], 0, set())
    summary['passed'] = True
except Exception as error:
    summary['error'] = f'{type(error).__name__}: {error}'
    print(summary['error'], flush=True)
finally:
    output.write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    print('Report:', output, flush=True)
if not summary['passed']:
    raise SystemExit(1)
