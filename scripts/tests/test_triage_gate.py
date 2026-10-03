import copy
import json
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest

PROJECT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT / 'scripts'))
from triage_gate import GateError, validate


class TriageGateTest(unittest.TestCase):
    def setUp(self):
        self.report = json.loads((PROJECT / 'validation/corpus-configured-after.json').read_text())
        self.triage = json.loads((PROJECT / 'validation/context-triage.json').read_text())
        self.manifest = json.loads((PROJECT / 'validation/corpus.json').read_text())

    def findings(self):
        return [run for run in self.report['runs'] if run['result']['findings']]

    def test_reviewed_corpus_preserves_twelve_errors(self):
        self.assertEqual(validate(self.report, self.triage, self.manifest),
                         {'findings': 12, 'falsePositives': 0, 'knownErrorsPreserved': 12})

    def test_losing_one_occurrence_of_repeated_reference_fails(self):
        run = self.findings()[0]
        self.assertEqual(run['result']['findings'][0]['reference'], run['result']['findings'][1]['reference'])
        run['result']['findings'].pop(0)
        with self.assertRaisesRegex(GateError, 'Known errors disappeared'):
            validate(self.report, self.triage, self.manifest)

    def test_unreviewed_occurrence_on_same_line_fails(self):
        run = self.findings()[0]
        extra = copy.deepcopy(run['result']['findings'][0])
        extra['column'] += 1
        run['result']['findings'].append(extra)
        with self.assertRaisesRegex(GateError, 'Unreviewed findings'):
            validate(self.report, self.triage, self.manifest)

    def test_input_failure_or_missing_run_cannot_pass(self):
        for report in (dict(self.report, runs=self.report['runs'][:-1]),
                       dict(self.report, runs=[dict(self.report['runs'][0], error='timeout')] + self.report['runs'][1:])):
            with self.assertRaises(GateError):
                validate(report, self.triage, self.manifest)

    def test_duplicate_or_mismatched_triage_fails(self):
        self.triage['findings'].append(copy.deepcopy(self.triage['findings'][0]))
        with self.assertRaisesRegex(GateError, 'Duplicate triage'):
            validate(self.report, self.triage, self.manifest)
        self.triage['findings'].pop()
        self.triage['findings'][0]['commit'] = 'bad-pin'
        with self.assertRaisesRegex(GateError, 'Triage commit'):
            validate(self.report, self.triage, self.manifest)

    def test_excess_false_positives_fail(self):
        self.triage['findings'][0]['classification'] = 'false-positive'
        with self.assertRaisesRegex(GateError, 'False-positive fraction'):
            validate(self.report, self.triage, self.manifest)

    def test_runner_writes_input_failure_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / 'manifest.json'
            manifest.write_text(json.dumps({'repositories': self.manifest['repositories'][:1]}))
            jar = root / 'unused.jar'
            jar.write_bytes(b'Never executed because the offline cache is missing')
            output = root / 'reports/corpus.json'
            run = subprocess.run([sys.executable, str(PROJECT / 'scripts/corpus.py'), '--offline',
                                  '--manifest', str(manifest), '--jar', str(jar),
                                  '--cache', str(root / 'empty-cache'), '--output', str(output)],
                                 capture_output=True, text=True)
            self.assertEqual(run.returncode, 2, run.stderr)
            report = json.loads(output.read_text())
            self.assertEqual(report['summary']['errors'], 1)
            self.assertIn('Missing offline cache', report['runs'][0]['error'])

    def test_legacy_columnless_triage_still_preserves_errors(self):
        report = json.loads((PROJECT / 'validation/corpus-after.json').read_text())
        triage = json.loads((PROJECT / 'validation/triage.json').read_text())
        self.assertEqual(validate(report, triage, self.manifest)['knownErrorsPreserved'], 3)


if __name__ == '__main__':
    unittest.main()
