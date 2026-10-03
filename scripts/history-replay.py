#!/usr/bin/env python3
"""Replay reconstructed or unchanged naturally stale upstream documentation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from history_gate import validate

PROJECT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--manifest', type=Path, default=PROJECT / 'validation/history.json')
parser.add_argument('--jar', type=Path, default=PROJECT / 'javadrift-cli/target/javadrift.jar')
parser.add_argument('--java', default='java')
parser.add_argument('--cache', type=Path, default=Path(tempfile.gettempdir()) / 'javadrift-history-cache')
parser.add_argument('--output', type=Path, default=PROJECT / 'validation/history-results.json')
parser.add_argument('--offline', action='store_true')
args = parser.parse_args()
args.cache.mkdir(parents=True, exist_ok=True)
jar = args.jar.resolve()
manifest = json.loads(args.manifest.read_text())

def git(checkout, *arguments):
    return subprocess.check_output(['git', '-C', str(checkout), *arguments], stderr=subprocess.PIPE)

def run(root, config, base):
    command = [args.java, '-Xmx768m', '-jar', str(jar), 'check', '--root', str(root),
               '--config', str(config), '--since', base, '--format', 'json']
    process = subprocess.run(command, text=True, capture_output=True, timeout=120)
    if process.returncode not in (0, 1) or not process.stdout.lstrip().startswith('{'):
        raise RuntimeError(process.stderr or process.stdout)
    return json.loads(process.stdout)

rows = []
for case in manifest['cases']:
    repo = case['repository']
    cache = args.cache / repo.replace('/', '--')
    if not (cache / '.git').exists():
        if args.offline:
            raise RuntimeError(f'Missing history cache: {repo}')
        subprocess.run(['git', 'clone', '--no-checkout',
                        f'https://github.com/{repo}.git', str(cache)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    for commit in (case['base'], case['head'], case.get('fixedHead', case['head'])):
        if subprocess.run(['git', '-C', str(cache), 'cat-file', '-e', f'{commit}^{{commit}}'],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
            if args.offline:
                raise RuntimeError(f'Missing history commit: {commit}')
            git(cache, 'fetch', 'origin', commit)
    # Native Git can hydrate partial-cache objects; JGit cannot lazily fetch them.
    # Materialize every pinned tree before sharing its object database with the CLI.
    for commit in dict.fromkeys((case['base'], case['head'], case.get('fixedHead', case['head']))):
        subprocess.run(['git', '-C', str(cache), 'archive', '--format=tar', commit],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                       env={**os.environ, **({'GIT_NO_LAZY_FETCH': '1'} if args.offline else {})})
    with tempfile.TemporaryDirectory(prefix='javadrift-replay-') as directory:
        root = Path(directory) / 'checkout'
        subprocess.run(['git', 'clone', '--shared', '--no-checkout', '--no-tags', str(cache), str(root)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        git(root, 'checkout', '--detach', case['head'])
        doc = root / case['document']
        natural = case['methodology'] == 'natural-stale-then-doc-fix'
        if natural:
            for earlier, later in ((case['base'], case['head']), (case['head'], case['fixedHead'])):
                subprocess.run(['git', '-C', str(cache), 'merge-base', '--is-ancestor', earlier, later],
                               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        old = git(cache, 'show', f"{case['head'] if natural else case['base']}:{case['document']}")
        current = git(cache, 'show', f"{case.get('fixedHead', case['head'])}:{case['document']}")
        config = Path(directory) / 'replay.yml'
        config.write_text('docs:\n  include: [' + json.dumps(case['document']) + ']\nchecks:\n' +
                          ''.join(f'  JD{i:03}: off\n' for i in range(1, 10) if i != 4))
        if natural:
            if old == current:
                raise ValueError(f"Natural history case has no documentation change: {case['id']}")
        else:
            doc.write_bytes(old)
        stale = run(root, config, case['base'])
        if natural:
            git(root, 'checkout', '--detach', '--force', case['fixedHead'])
        else:
            doc.write_bytes(current)
        fixed = run(root, config, case['base'])
        missing, problems = validate(case, stale['findings'], fixed['findings'])
        row = dict(case, staleFindings=stale['findings'], fixedFindings=fixed['findings'],
                   passed=not problems, missingTargets=missing, reviewProblems=problems)
        rows.append(row)
        print(case['id'], 'PASS' if row['passed'] else 'FAIL', 'stale', len(stale['findings']),
              'fixed', len(fixed['findings']), flush=True)
report = {'schemaVersion': 1, 'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
          'methodology': 'Case methodology distinguishes reconstructed parent docs from unchanged naturally stale upstream revisions followed by actual documentation-fix commits. No synthetic commits or invented API examples.',
          'runs': rows, 'passed': all(r['passed'] for r in rows)}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, indent=2) + '\n')
if not report['passed']:
    raise SystemExit(1)
