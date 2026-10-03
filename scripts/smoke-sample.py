#!/usr/bin/env python3
"""Exercise local CLI, Maven and Gradle adoption without changing the sample."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

PROJECT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--java', default='java')
parser.add_argument('--maven', default='mvn')
parser.add_argument('--skip-build', action='store_true', help='Use an already built CLI and installed Maven plugin')
parser.add_argument('--output', type=Path, default=Path(tempfile.gettempdir()) / 'javadrift-sample-results/report.json')
args = parser.parse_args()
output = args.output.resolve()
output.parent.mkdir(parents=True, exist_ok=True)
logs = output.parent / (output.stem + '-logs')
logs.mkdir(exist_ok=True)
jar = PROJECT / 'javadrift-cli/target/javadrift.jar'
wrapper = PROJECT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
report = {'schemaVersion': 1, 'scope': 'Local source-built CLI, locally installed Maven plugin and included Gradle build; temporary consumers',
          'runs': [], 'passed': False}
expected = {('JD002', 'demo.Greeter#salute'), ('JD002', 'demo.Welcomer#hello')}


def run(name, command, root, exit_code=0, json_file=None, stale=False):
    if json_file is not None:
        json_file.unlink(missing_ok=True)
    started = time.perf_counter()
    process = subprocess.run(command, cwd=root, text=True, capture_output=True, timeout=300)
    log = logs / (name + '.log')
    log.write_text(process.stdout + process.stderr)
    row = {'name': name, 'exitCode': process.returncode, 'expectedExitCode': exit_code,
           'seconds': round(time.perf_counter() - started, 3), 'log': log.name}
    report['runs'].append(row)
    if process.returncode != exit_code:
        raise RuntimeError(f'{name}: expected exit {exit_code}, got {process.returncode}; see {log}')
    if json_file is not None:
        result = json.loads(json_file.read_text())
        findings = {(f['checkId'], f['reference']) for f in result['findings']}
        wanted = expected if stale else set()
        if findings != wanted or len(result['findings']) != len(wanted) or result['documents'] != 1 or result['types'] != 2:
            raise RuntimeError(f'{name}: unexpected documentation report: {result}; see {log}')
        row['result'] = result
    print(name, 'PASS', flush=True)


def select(root, variant):
    shutil.copyfile(root / 'variants' / (variant + '.adoc'), root / 'docs/guide.adoc')


def compiled(root, files):
    for file in files:
        if not (root / file).is_file():
            raise RuntimeError(f'Sample class was not compiled: {file}')


try:
    if not args.skip_build:
        run('build-and-install', [args.maven, '-B', '-DskipTests', 'install'], PROJECT)
    if not jar.is_file():
        raise RuntimeError('Build the CLI before using --skip-build')
    report['jarSha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
    with tempfile.TemporaryDirectory(prefix='javadrift-adoption-') as directory:
        roots = {}
        for adapter in ('cli', 'maven', 'gradle'):
            root = Path(directory) / adapter
            shutil.copytree(PROJECT / 'examples/local-adoption', root,
                            ignore=shutil.ignore_patterns('target', 'build', '.gradle'))
            roots[adapter] = root
        root = roots['cli']
        cli_report = root / 'report.json'
        cli = [args.java, '-jar', str(jar), 'check', '--root', str(root), '--format', 'json', '--output', str(cli_report)]
        run('cli-valid', cli, root, json_file=cli_report)
        select(root, 'stale')
        run('cli-stale', cli, root, exit_code=1, json_file=cli_report, stale=True)
        run('cli-warn-only', cli + ['--warn-only'], root, json_file=cli_report, stale=True)
        select(root, 'valid')
        run('cli-restored', cli, root, json_file=cli_report)

        root = roots['maven']
        maven = [args.maven, '-B', 'verify']
        maven_report = root / 'target/javadrift.json'
        run('maven-valid', maven, root, json_file=maven_report)
        compiled(root, ['target/classes/demo/Greeter.class', 'target/classes/demo/Welcomer.class'])
        select(root, 'stale')
        run('maven-stale', maven, root, exit_code=1, json_file=maven_report, stale=True)
        run('maven-warn-only', maven + ['-Djavadrift.warnOnly=true'], root, json_file=maven_report, stale=True)
        select(root, 'valid')
        run('maven-restored', maven, root, json_file=maven_report)

        root = roots['gradle']
        gradle = [str(wrapper), '-p', str(root), '-PjavadriftSource=' + str(PROJECT), 'check', '--no-daemon']
        gradle_report = root / 'build/reports/javadrift/report.txt'
        run('gradle-valid', gradle, root, json_file=gradle_report)
        compiled(root, ['build/classes/java/main/demo/Greeter.class', 'build/classes/kotlin/main/demo/Welcomer.class'])
        select(root, 'stale')
        run('gradle-stale', gradle, root, exit_code=1, json_file=gradle_report, stale=True)
        run('gradle-warn-only', gradle + ['-PwarnOnly=true'], root, json_file=gradle_report, stale=True)
        select(root, 'valid')
        run('gradle-restored', gradle, root, json_file=gradle_report)
    report['passed'] = True
except Exception as error:
    report['error'] = f'{type(error).__name__}: {error}'
    print(report['error'], flush=True)
finally:
    output.write_text(json.dumps(report, indent=2) + '\n')
    print('Report:', output, flush=True)
if not report['passed']:
    raise SystemExit(1)
