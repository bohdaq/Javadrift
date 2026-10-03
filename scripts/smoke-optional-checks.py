#!/usr/bin/env python3
"""Validate opt-in snippet/property checks with a built Jackson-backed sample."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java', default='java')
parser.add_argument('--maven', default='mvn')
parser.add_argument('--jar', type=Path, default=PROJECT / 'javadrift-cli/target/javadrift.jar')
parser.add_argument('--skip-build', action='store_true', help='Use an existing CLI JAR; the sample is still built')
parser.add_argument('--output', type=Path, default=Path(tempfile.gettempdir()) / 'javadrift-optional-checks/report.json')
args = parser.parse_args()
output = args.output.resolve()
output.parent.mkdir(parents=True, exist_ok=True)
logs = output.parent / (output.stem + '-logs')
logs.mkdir(exist_ok=True)
report = {'schemaVersion': 1, 'os': platform.system(), 'runs': [], 'passed': False}
maven = shutil.which(args.maven) or args.maven
java = shutil.which(args.java) or args.java


def run(name, command, root, expected=0):
    process = subprocess.run(command, cwd=root, text=True, encoding='utf-8', errors='replace',
                             capture_output=True, timeout=300)
    (logs / (name + '.log')).write_text(process.stdout + process.stderr, encoding='utf-8')
    report['runs'].append({'name': name, 'exitCode': process.returncode, 'expectedExitCode': expected})
    if process.returncode != expected:
        raise RuntimeError(f'{name}: expected exit {expected}, got {process.returncode}; see {logs / (name + ".log")}')
    return process


def identity(finding):
    return tuple(finding[k] for k in ('checkId', 'file', 'line', 'column', 'reference'))


def class_files(root):
    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in root.rglob('*.class')}


try:
    triage_path = PROJECT / 'validation/optional-checks-triage.json'
    triage = json.loads(triage_path.read_text(encoding='utf-8'))
    if triage['schemaVersion'] != 1 or triage['fixture'] != 'examples/optional-checks':
        raise RuntimeError('Unsupported optional-checks triage')
    reviewed = triage['findings']
    if len(reviewed) != 3 or len({identity(f) for f in reviewed}) != 3 or any(
            f['classification'] != 'true-positive' or not f['evidence'] for f in reviewed):
        raise RuntimeError('The three reviewed findings need unique identities and evidence')
    report['triageSha256'] = hashlib.sha256(triage_path.read_bytes()).hexdigest()
    if not args.skip_build:
        run('build-cli', [maven, '-B', '-DskipTests', '-pl', 'javadrift-cli', '-am', 'package'], PROJECT)
    jar = args.jar.resolve()
    report['jarSha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
    version = run('java-version', [java, '-version'], PROJECT)
    report['javaVersion'] = (version.stdout + version.stderr).strip()
    with tempfile.TemporaryDirectory(prefix='javadrift optional checks ') as directory:
        base = Path(directory)
        root = base / 'project files'
        shutil.copytree(PROJECT / triage['fixture'], root, ignore=shutil.ignore_patterns('target', 'build', '.gradle'))
        for name, digest in triage['fixtureHashes'].items():
            if hashlib.sha256((root / 'variants' / name).read_text(encoding='utf-8').encode('utf-8')).hexdigest() != digest:
                raise RuntimeError(f'Review changed fixture before updating triage: {name}')
        run('build-sample', [maven, '-B', 'compile', 'dependency:build-classpath',
                             '-Dmdep.outputFile=target/dependency-classpath.txt'], root)
        if not (root / 'target/classes/demo/ReportService.class').is_file():
            raise RuntimeError('Sample project class was not compiled')
        native_classpath = (root / 'target/dependency-classpath.txt').read_text(encoding='utf-8').strip()
        dependencies = [Path(entry) for entry in native_classpath.split(os.pathsep)]
        if {p.name for p in dependencies} != {f'jackson-{name}-2.18.3.jar' for name in ('databind', 'core', 'annotations')} or len(dependencies) != 3:
            raise RuntimeError('Expected exactly the three pinned Jackson dependency JARs')
        copied = []
        report['dependencies'] = []
        for dependency in dependencies:
            target = base / 'dependency files' / dependency.name
            target.parent.mkdir(exist_ok=True)
            shutil.copyfile(dependency, target)
            copied.append(str(target))
            report['dependencies'].append({'name': dependency.name, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest()})
        classpath = os.pathsep.join(copied)
        before = class_files(root)
        config = (root / 'javadrift.yml').read_text(encoding='utf-8')
        configs = {}
        variations = {
            'enabled': config,
            'off': config.replace('JD008: error', 'JD008: off').replace('JD009: error', 'JD009: off'),
            'defaults': config.replace('checks:\n  JD008: error\n  JD009: error\n', ''),
            'snippets': config.replace('JD009: error', 'JD009: off'),
            'properties': config.replace('JD008: error', 'JD008: off'),
            'warnings': config.replace('JD009: error', 'JD009: warning'),
            'warning-fails': config.replace('JD009: error', 'JD009: warning') + '\nfailOn: warning\n',
        }
        for name, text in variations.items():
            target = root / 'config files' / (name + '.yml')
            target.parent.mkdir(exist_ok=True)
            target.write_text(text, encoding='utf-8')
            configs[name] = target

        def select(guide='valid', settings='valid'):
            shutil.copyfile(root / f'variants/{guide}-guide.md', root / 'docs/guide.md')
            shutil.copyfile(root / f'variants/{settings}-settings.adoc', root / 'docs/settings.adoc')

        def check(name, wanted=(), expected=0, configuration='enabled', warn=False, dependencies=True, missing=False, setup=False):
            target = logs / (name + '.json')
            target.unlink(missing_ok=True)
            command = [java, '-jar', str(jar), 'check', '--root', str(root), '--config', str(configs[configuration]),
                       '--format', 'json', '--output', str(target)]
            if dependencies:
                command += ['--classpath', str(base / 'missing dependency.jar') if missing else classpath]
            if warn:
                command.append('--warn-only')
            process = run(name, command, root, expected)
            if expected == 2:
                if target.exists() or not process.stderr.strip():
                    raise RuntimeError(f'{name}: input error must have a diagnostic and no report')
            else:
                result = json.loads(target.read_text(encoding='utf-8'))
                if result['documents'] != 2 or result['types'] != 1:
                    raise RuntimeError(f'{name}: wrong input counts: {result}')
                if setup:
                    text = (root / 'variants/valid-guide.md').read_text(encoding='utf-8')
                    blocks = {block.strip() for block in re.findall(r'(?ms)^```java\n(.*?)^```', text)
                              if '// javadrift:skip' not in block}
                    if len(blocks) != 5 or len(result['findings']) != 5 or {f['reference'] for f in result['findings']} != blocks or any(
                            f['checkId'] != 'JD008' or f['file'] != 'docs/guide.md' for f in result['findings']):
                        raise RuntimeError(f'{name}: missing classpath must fail each compiled block: {result}')
                elif {identity(f) for f in result['findings']} != {identity(f) for f in wanted} or len(result['findings']) != len(wanted):
                    raise RuntimeError(f'{name}: missing known error or unreviewed finding: {result}')
                severity = 'WARNING' if configuration in ('warnings', 'warning-fails') else 'ERROR'
                if any(f['severity'] != severity for f in result['findings']):
                    raise RuntimeError(f'{name}: wrong finding severity')
                report['runs'][-1]['findings'] = len(result['findings'])
            if class_files(root) != before:
                raise RuntimeError(f'{name}: snippet compilation wrote or changed class files')
            print(name, 'PASS', flush=True)

        snippets = [f for f in reviewed if f['checkId'] == 'JD008']
        properties = [f for f in reviewed if f['checkId'] == 'JD009']
        if len(snippets) != 1 or len(properties) != 2:
            raise RuntimeError('Reviewed check distribution changed')
        check('valid-enabled')
        select(guide='stale')
        check('snippet-stale', snippets, 1)
        check('snippet-warn-only', snippets, warn=True)
        select(settings='stale')
        check('properties-stale', properties, 1)
        check('properties-warning', properties, configuration='warnings')
        check('properties-warning-fails', properties, 1, configuration='warning-fails')
        check('properties-warning-warn-only', properties, configuration='warning-fails', warn=True)
        select('stale', 'stale')
        check('both-stale', reviewed, 1)
        check('both-warn-only', reviewed, warn=True)
        check('both-disabled', configuration='off')
        check('default-checks-off', configuration='defaults')
        check('snippets-only', snippets, 1, configuration='snippets')
        check('properties-only', properties, 1, configuration='properties')
        select()
        check('restored', ())
        check('missing-dependencies', expected=1, dependencies=False, setup=True)
        check('missing-dependencies-warn-only', dependencies=False, warn=True, setup=True)
        check('missing-classpath-file', expected=2, missing=True)
        check('missing-classpath-file-warn-only', expected=2, missing=True, warn=True)
        malformed = root / 'src/main/resources/malformed.yml'
        malformed.write_text('report: [unclosed\n', encoding='utf-8')
        check('malformed-resource', expected=2)
        check('malformed-resource-warn-only', expected=2, warn=True)
        check('disabled-property-parser', configuration='snippets')
        malformed.unlink()
        check('final-valid')
    report['passed'] = True
except Exception as error:
    report['error'] = f'{type(error).__name__}: {error}'
    print(report['error'], flush=True)
finally:
    output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('Report:', output, flush=True)
if not report['passed']:
    raise SystemExit(1)
