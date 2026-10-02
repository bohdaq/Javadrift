#!/usr/bin/env python3
"""Reproducible 500-document / 5,000-class source-index benchmark."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser()
parser.add_argument('--jar', type=Path, default=Path('javadrift-cli/target/javadrift.jar'))
parser.add_argument('--java', default='java')
parser.add_argument('--max-seconds', type=float)
args = parser.parse_args()
jar = args.jar.resolve()
if not jar.is_file():
    parser.error('Build the CLI jar with mvn package first')
with tempfile.TemporaryDirectory(prefix='javadrift-benchmark-') as directory:
    root = Path(directory)
    source = root / 'src/main/java/benchmark'
    docs = root / 'docs'
    source.mkdir(parents=True)
    docs.mkdir()
    for i in range(5000):
        (source / f'Type{i}.java').write_text(
            f'package benchmark; public class Type{i} {{ public void place() {{}} }}\n')
    for i in range(500):
        (docs / f'guide-{i}.md').write_text(f'`benchmark.Type{i}` `Type{i}#place`\n')
    started = time.perf_counter()
    result = subprocess.run([args.java, '-Xmx512m', '-jar', str(jar), 'check',
                             '--root', str(root), '--format', 'json'],
                            text=True, capture_output=True, check=True)
    elapsed = time.perf_counter() - started
    report = json.loads(result.stdout)
    assert report['documents'] == 500 and report['types'] == 5000
    assert report['findings'] == []
    print(json.dumps({'documents': 500, 'classes': 5000,
                      'wallSeconds': round(elapsed, 3), 'findings': 0}))
    if args.max_seconds is not None and elapsed > args.max_seconds:
        raise SystemExit(f'Budget exceeded: {elapsed:.3f}s > {args.max_seconds}s')
