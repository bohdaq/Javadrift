#!/usr/bin/env python3
"""Repeated source scans with per-JVM peak RSS and an optional reference JAR."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import signal
import statistics
import subprocess
import sys
import tempfile
import threading
import time
from benchmark_fixtures import generate

PROJECT = Path(__file__).resolve().parents[1]


def measure(command, stdout, stderr, timeout):
    """Reap this child with wait4; RUSAGE_CHILDREN would reuse earlier peaks."""
    expired = threading.Event()
    with stdout.open('wb') as out, stderr.open('wb') as err:
        started = time.perf_counter()
        process = subprocess.Popen(command, stdout=out, stderr=err)

        def kill():
            expired.set()
            try:
                os.kill(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass

        timer = threading.Timer(timeout, kill)
        timer.daemon = True
        timer.start()
        try:
            _, status, usage = os.wait4(process.pid, 0)
            process.returncode = os.waitstatus_to_exitcode(status)
            elapsed = time.perf_counter() - started
        except BaseException:
            kill()
            _, status, _ = os.wait4(process.pid, 0)
            process.returncode = os.waitstatus_to_exitcode(status)
            raise
        finally:
            timer.cancel()
            timer.join()
        if expired.is_set():
            raise TimeoutError(f'JVM exceeded {timeout} seconds; see {stderr}')
    peak_bytes = usage.ru_maxrss * (1 if sys.platform == 'darwin' else 1024)
    if peak_bytes <= 0:
        raise RuntimeError('The operating system did not report a peak RSS')
    return {'wallSeconds': round(elapsed, 6), 'peakRssMiB': round(peak_bytes / 1024 ** 2, 3),
            'exitCode': process.returncode}


def summarize(runs):
    return {'repeats': len(runs),
            'medianWallSeconds': statistics.median(r['wallSeconds'] for r in runs),
            'minWallSeconds': min(r['wallSeconds'] for r in runs),
            'maxWallSeconds': max(r['wallSeconds'] for r in runs),
            'medianPeakRssMiB': statistics.median(r['peakRssMiB'] for r in runs),
            'maxPeakRssMiB': max(r['peakRssMiB'] for r in runs)}


def compare(current, reference, time_ratio=1.5, time_floor=0.75, memory_ratio=1.25, memory_floor=32):
    for values in (current, reference):
        for metric in ('medianWallSeconds', 'medianPeakRssMiB'):
            if not math.isfinite(values[metric]) or values[metric] <= 0:
                raise ValueError(f'Invalid benchmark metric: {metric}')
    limits = {'medianWallSeconds': max(reference['medianWallSeconds'] * time_ratio,
                                       reference['medianWallSeconds'] + time_floor),
              'medianPeakRssMiB': max(reference['medianPeakRssMiB'] * memory_ratio,
                                     reference['medianPeakRssMiB'] + memory_floor)}
    failures = []
    for metric, limit in limits.items():
        value = current[metric]
        if not math.isfinite(value) or not math.isfinite(limit) or value <= 0 or limit <= 0:
            raise ValueError(f'Invalid benchmark metric: {metric}')
        if value > limit:
            failures.append(f'{metric}: {value:.3f} exceeds {limit:.3f}')
    return {'limits': limits, 'failures': failures}


def positive(value):
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise argparse.ArgumentTypeError('Must be finite and positive')
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=PROJECT / 'javadrift-cli/target/javadrift.jar')
    parser.add_argument('--reference-jar', type=Path)
    parser.add_argument('--java', default='java')
    parser.add_argument('--profile', choices=('java', 'mixed', 'all'), default='java')
    parser.add_argument('--classes', type=int, default=5000)
    parser.add_argument('--documents', type=int, default=500)
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--heap-mib', type=int, default=512)
    parser.add_argument('--timeout', type=positive, default=120)
    parser.add_argument('--max-seconds', type=positive)
    parser.add_argument('--time-ratio', type=positive, default=1.5)
    parser.add_argument('--time-floor-seconds', type=positive, default=0.75)
    parser.add_argument('--memory-ratio', type=positive, default=1.25)
    parser.add_argument('--memory-floor-mib', type=positive, default=32)
    parser.add_argument('--output', type=Path, default=Path(tempfile.gettempdir()) / 'javadrift-benchmark/report.json')
    args = parser.parse_args()
    if sys.platform not in ('linux', 'darwin') or not hasattr(os, 'wait4'):
        parser.error('Peak RSS measurement requires Linux or macOS')
    if args.classes < 2 or min(args.documents, args.repeats, args.heap_mib) < 1:
        parser.error('Need at least two classes and positive documents, repeats and heap')
    if min(args.time_ratio, args.memory_ratio) < 1:
        parser.error('Regression ratios must be at least 1')
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    logs = output.parent / (output.stem + '-logs')
    logs.mkdir(exist_ok=True)
    report = {'schemaVersion': 1, 'environment': {'os': platform.system(), 'machine': platform.machine(),
               'logicalCpus': os.cpu_count(), 'peakMemoryMethod': 'wait4.ru_maxrss'},
              'heapMiB': args.heap_mib, 'cases': [], 'passed': False,
              'thresholds': {'timeRatio': args.time_ratio, 'timeFloorSeconds': args.time_floor_seconds,
                             'memoryRatio': args.memory_ratio, 'memoryFloorMiB': args.memory_floor_mib}}
    try:
        jars = {'current': args.jar.resolve()}
        if args.reference_jar:
            jars['reference'] = args.reference_jar.resolve()
        report['jarSha256'] = {name: hashlib.sha256(jar.read_bytes()).hexdigest() for name, jar in jars.items()}
        version = subprocess.run([args.java, '-version'], text=True, capture_output=True, check=True, timeout=15)
        report['environment']['javaVersion'] = (version.stdout + version.stderr).strip()
        profiles = ('java', 'mixed') if args.profile == 'all' else (args.profile,)
        for profile in profiles:
            with tempfile.TemporaryDirectory(prefix='javadrift-benchmark-') as directory:
                root = Path(directory)
                case = {'fixture': generate(root, profile, args.classes, args.documents),
                        'runs': {name: [] for name in jars}, 'summary': {}}
                report['cases'].append(case)
                for repeat in range(args.repeats):
                    # Alternate order within pairs so one artifact does not always run first.
                    order = list(jars) if repeat % 2 == 0 else list(reversed(jars))
                    for label in order:
                        name = f'{profile}-{label}-{repeat + 1}'
                        stdout, stderr = logs / (name + '.json'), logs / (name + '.stderr.log')
                        command = [args.java, f'-Xmx{args.heap_mib}m', '-jar', str(jars[label]),
                                   'check', '--root', str(root), '--format', 'json']
                        measured = measure(command, stdout, stderr, args.timeout)
                        case['runs'][label].append(measured)
                        if measured['exitCode'] != 0:
                            raise RuntimeError(f'{name}: CLI exited {measured["exitCode"]}; see {stderr}')
                        result = json.loads(stdout.read_text(encoding='utf-8'))
                        if result['documents'] != args.documents or result['types'] != args.classes or result['findings']:
                            raise RuntimeError(f'{name}: incorrect fixture scan; see {stdout}')
                        measured['documents'], measured['types'], measured['findings'] = result['documents'], result['types'], 0
                        print(f'{name}: {measured["wallSeconds"]:.3f}s, {measured["peakRssMiB"]:.1f} MiB peak RSS', flush=True)
                case['summary'] = {label: summarize(runs) for label, runs in case['runs'].items()}
                case['failures'] = []
                current = case['summary']['current']
                if args.max_seconds and current['medianWallSeconds'] > args.max_seconds:
                    case['failures'].append(f'Median time exceeds {args.max_seconds} seconds')
                if 'reference' in jars:
                    case['comparison'] = compare(current, case['summary']['reference'], args.time_ratio,
                                                 args.time_floor_seconds, args.memory_ratio, args.memory_floor_mib)
                    case['failures'].extend(case['comparison']['failures'])
                for failure in case['failures']:
                    print(f'{profile}: REGRESSION {failure}', flush=True)
        report['passed'] = not any(c['failures'] for c in report['cases'])
    except Exception as error:
        report['error'] = f'{type(error).__name__}: {error}'
        print(report['error'], flush=True)
    finally:
        output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print('Report:', output, flush=True)
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
