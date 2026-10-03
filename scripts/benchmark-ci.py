#!/usr/bin/env python3
"""Build a Git reference and current CLI, then compare on the same runner."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tarfile
import tempfile

PROJECT = Path(__file__).resolve().parents[1]


def run(command, cwd, log, timeout=300):
    with log.open('wb') as stream:
        process = subprocess.Popen(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            return process.wait(timeout=timeout)
        except BaseException:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
            raise


def summary(output, builds):
    destination = os.environ.get('GITHUB_STEP_SUMMARY')
    if not destination:
        return
    lines = ['## Javadrift performance', '',
             f'Current: `{builds.get("currentCommit", "unresolved")}`',
             f'Reference: `{builds.get("referenceCommit", "unresolved")}`', '']
    if output.exists():
        result = json.loads(output.read_text(encoding='utf-8'))
        lines += ['| Profile | Current median (s) | Reference median (s) | Current peak RSS median (MiB) | Reference peak RSS median (MiB) |',
                  '|---|---:|---:|---:|---:|']
        for case in result['cases']:
            if not case['summary']:
                continue
            current, reference = case['summary']['current'], case['summary']['reference']
            lines.append(f'| {case["fixture"]["profile"]} | {current["medianWallSeconds"]:.3f} | '
                         f'{reference["medianWallSeconds"]:.3f} | {current["medianPeakRssMiB"]:.1f} | '
                         f'{reference["medianPeakRssMiB"]:.1f} |')
            for failure in case.get('failures', []):
                lines += ['', f'- {case["fixture"]["profile"]}: {failure}']
        if 'error' in result:
            lines += ['', 'The scan failed; inspect the saved JSON and command logs.']
    lines += ['', 'Gate: time increases above both 50% and 0.75 s, or memory above both 25% and 32 MiB.',
              '', 'Builds and scans use the same runner, JDK, heap and generated inputs. Full per-run metrics and logs are in the artifact.',
              '', 'Result: ' + ('passed' if builds['passed'] else 'failed'), '']
    with Path(destination).open('a', encoding='utf-8') as stream:
        stream.write('\n'.join(lines))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-ref', default='HEAD^')
    parser.add_argument('--java', default='java')
    parser.add_argument('--maven', default='mvn')
    parser.add_argument('--repeats', type=int, default=5)
    parser.add_argument('--output', type=Path, default=PROJECT / 'validation-results/performance.json')
    args = parser.parse_args()
    if sys.platform not in ('linux', 'darwin') or args.repeats < 3:
        parser.error('CI comparison needs Linux/macOS and at least three repeats')
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.unlink(missing_ok=True)
    logs = output.parent / (output.stem + '-build-logs')
    logs.mkdir(exist_ok=True)
    builds = {'schemaVersion': 1, 'passed': False}
    try:
        base_ref = args.base_ref
        if len(base_ref) in (40, 64) and not base_ref.strip('0'):
            base_ref = 'HEAD^'
        def commit(ref):
            return subprocess.run(['git', 'rev-parse', '--verify', '--end-of-options', ref + '^{commit}'],
                                  cwd=PROJECT, text=True, capture_output=True, check=True, timeout=15).stdout.strip()
        builds['currentCommit'], builds['referenceCommit'] = commit('HEAD'), commit(base_ref)
        with tempfile.TemporaryDirectory(prefix='javadrift-reference-') as directory:
            temporary = Path(directory)
            archive = temporary / 'reference.tar'
            with archive.open('wb') as stream:
                subprocess.run(['git', 'archive', builds['referenceCommit']], cwd=PROJECT,
                               stdout=stream, stderr=subprocess.PIPE, check=True, timeout=60)
            reference = temporary / 'reference'
            reference.mkdir()
            with tarfile.open(archive) as snapshot:
                snapshot.extractall(reference, filter='data')
            for label, checkout in (('reference', reference), ('current', PROJECT)):
                command = [args.maven, '-B', '-DskipTests', '-pl', 'javadrift-cli', '-am', 'package']
                code = run(command, checkout, logs / (label + '-build.log'))
                builds[label + 'BuildExitCode'] = code
                if code:
                    raise RuntimeError(f'{label} build failed; see {logs / (label + "-build.log")}')
            command = [sys.executable, str(PROJECT / 'scripts/benchmark.py'), '--profile', 'all',
                       '--repeats', str(args.repeats), '--java', args.java,
                       '--jar', str(PROJECT / 'javadrift-cli/target/javadrift.jar'),
                       '--reference-jar', str(reference / 'javadrift-cli/target/javadrift.jar'), '--output', str(output)]
            code = run(command, PROJECT, logs / 'comparison.log', timeout=900)
            builds['benchmarkExitCode'] = code
            if not output.exists():
                raise RuntimeError('Benchmark did not save its report')
            result = json.loads(output.read_text(encoding='utf-8'))
            result['comparisonCommits'] = {'current': builds['currentCommit'], 'reference': builds['referenceCommit']}
            output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
            if code or not result['passed']:
                raise RuntimeError(f'Performance comparison failed; see {output}')
        builds['passed'] = True
    except Exception as error:
        builds['error'] = f'{type(error).__name__}: {error}'
        print(builds['error'], flush=True)
    finally:
        output.with_suffix('.build.json').write_text(json.dumps(builds, indent=2) + '\n', encoding='utf-8')
        summary(output, builds)
    print('Reports:', output, output.with_suffix('.build.json'), flush=True)
    return 0 if builds['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
