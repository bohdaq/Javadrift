#!/usr/bin/env python3
"""Reproducible Java and mixed Java/Kotlin source-index benchmarks."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import time
from benchmark_fixtures import generate

parser = argparse.ArgumentParser()
parser.add_argument('--jar', type=Path, default=Path('javadrift-cli/target/javadrift.jar'))
parser.add_argument('--java', default='java')
parser.add_argument('--max-seconds', type=float)
parser.add_argument('--profile', choices=('java', 'mixed', 'all'), default='java')
parser.add_argument('--classes', type=int, default=5000)
parser.add_argument('--documents', type=int, default=500)
args = parser.parse_args()
jar = args.jar.resolve()
if not jar.is_file():
    parser.error('Build the CLI jar with mvn package first')
for profile in (('java', 'mixed') if args.profile == 'all' else (args.profile,)):
    with tempfile.TemporaryDirectory(prefix='javadrift-benchmark-') as directory:
        root = Path(directory)
        fixture = generate(root, profile, args.classes, args.documents)
        started = time.perf_counter()
        result = subprocess.run([args.java, '-Xmx512m', '-jar', str(jar), 'check',
                                 '--root', str(root), '--format', 'json'],
                                text=True, capture_output=True, check=True, timeout=120)
        elapsed = time.perf_counter() - started
        report = json.loads(result.stdout)
        if report['documents'] != args.documents or report['types'] != args.classes or report['findings']:
            raise RuntimeError(f'Unexpected fixture scan: {report}')
        print(json.dumps({**fixture, 'wallSeconds': round(elapsed, 3), 'findings': 0}))
        if args.max_seconds is not None and elapsed > args.max_seconds:
            raise SystemExit(f'Budget exceeded: {elapsed:.3f}s > {args.max_seconds}s')
