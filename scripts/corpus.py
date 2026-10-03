#!/usr/bin/env python3
"""Scan pinned upstream source snapshots and require every finding to be triaged.

Only setup uses the network. No upstream builds, processors or examples execute.
Snapshots omit Git tags: snapshot JD006 release selection is tested separately.
"""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import time
from triage_gate import GateError, validate

PROJECT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--manifest', type=Path, default=PROJECT / 'validation/corpus.json')
parser.add_argument('--jar', type=Path, default=PROJECT / 'javadrift-cli/target/javadrift.jar')
parser.add_argument('--java', default='java')
parser.add_argument('--cache', type=Path, default=Path(tempfile.gettempdir()) / 'javadrift-validation')
parser.add_argument('--output', type=Path, default=PROJECT / 'validation/corpus-after.json')
parser.add_argument('--offline', action='store_true')
parser.add_argument('--expanded', action='store_true', help='Stress scan all Markdown/AsciiDoc files')
parser.add_argument('--require-triage', type=Path)
parser.add_argument('--context-config', type=Path, help='Explicit document-context overrides tied to corpus commit pins')
args = parser.parse_args()
args.cache.mkdir(parents=True, exist_ok=True)
jar = args.jar.resolve()
if not jar.is_file():
    parser.error('Build the CLI first')
manifest = json.loads(args.manifest.read_text())
contexts = {}
if args.context_config:
    context_manifest = json.loads(args.context_config.read_text())
    pins = {entry['repository']: entry['commit'] for entry in manifest['repositories']}
    for context in context_manifest['repositories']:
        repo = context['repository']
        if repo in contexts or pins.get(repo) != context['commit']:
            parser.error(f'Duplicate context or mismatched commit pin: {repo}')
        contexts[repo] = context['config']

def git(checkout, *arguments):
    return subprocess.check_output(['git', '-C', str(checkout), *arguments], stderr=subprocess.PIPE)

def scan_snapshot(entry):
    repo, commit = entry['repository'], entry['commit']
    checkout = args.cache / repo.replace('/', '--')
    if not (checkout / '.git').exists():
        if args.offline:
            raise RuntimeError(f'Missing offline cache: {repo}')
        subprocess.run(['git', 'clone', '--no-checkout', '--no-tags', '--filter=blob:none',
                        f'https://github.com/{repo}.git', str(checkout)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    if subprocess.run(['git', '-C', str(checkout), 'cat-file', '-e', f'{commit}^{{commit}}'],
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        if args.offline:
            raise RuntimeError(f'Missing pinned commit: {repo}@{commit}')
        git(checkout, 'fetch', '--no-tags', '--depth=1', 'origin', commit)
    with tempfile.TemporaryDirectory(prefix='javadrift-corpus-') as directory:
        root = Path(directory)
        archive = root / 'source.tar'
        archive.write_bytes(git(checkout, 'archive', '--format=tar', commit))
        with tarfile.open(archive) as contents:
            contents.extractall(root, filter='data')
        archive.unlink()
        command = [args.java, '-Xmx768m', '-jar', str(jar), 'check', '--root', str(root), '--format', 'json']
        if args.expanded or repo in contexts:
            configuration = json.loads(json.dumps(contexts.get(repo, {})))
            if args.expanded:
                docs = configuration.setdefault('docs', {})
                docs.update(include=['**/*.md', '**/*.adoc'],
                            exclude=['**/target/**', '**/build/**', '**/.git/**', '**/node_modules/**'])
            config = root / 'corpus-scope.yml'
            config.write_text(json.dumps(configuration))
            command += ['--config', str(config)]
        started = time.perf_counter()
        process = subprocess.run(command, text=True, capture_output=True, timeout=120)
        row = {'repository': repo, 'commit': commit, 'exitCode': process.returncode,
               'seconds': round(time.perf_counter() - started, 3)}
        if process.returncode in (0, 1) and process.stdout.lstrip().startswith('{'):
            row['result'] = json.loads(process.stdout)
            if repo in contexts:
                row['documentContext'] = contexts[repo]
        else:
            row['error'] = process.stderr.strip() or process.stdout.strip()
        return row

def scan(entry):
    try:
        return scan_snapshot(entry)
    except Exception as error:
        return {'repository': entry['repository'], 'commit': entry['commit'],
                'exitCode': 2, 'error': f'{type(error).__name__}: {error}'}

with concurrent.futures.ThreadPoolExecutor(max_workers=4) as workers:
    rows = list(workers.map(scan, manifest['repositories']))
findings = [(row, f) for row in rows for f in row.get('result', {}).get('findings', [])]
errors = [r for r in rows if 'error' in r]
report = {'schemaVersion': 1, 'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
          'scope': 'all-repository-docs' if args.expanded else 'default-docs',
          'mode': 'full/source-only/no-Git-tags', 'runs': rows,
          'summary': {'repositories': len(rows), 'errors': len(errors),
                      'documents': sum(r.get('result', {}).get('documents', 0) for r in rows),
                      'findings': len(findings)}}
if args.context_config:
    report['contextConfigSha256'] = hashlib.sha256(args.context_config.read_bytes()).hexdigest()
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report['summary']))
for r in errors:
    print(r['repository'], r['error'][:300])
if errors:
    raise SystemExit(2)
if args.require_triage:
    try:
        outcome = validate(report, json.loads(args.require_triage.read_text()), manifest)
    except GateError as error:
        print(error)
        raise SystemExit(2)
    print(f'Triaged {outcome["findings"]} findings; {outcome["falsePositives"]} false positives; '
          f'{outcome["knownErrorsPreserved"]} known errors preserved. This is observed precision, not recall.')
