"""Regression guards for measurement correctness and non-flaky thresholds."""
import math
import json
from pathlib import Path
import sys
import tempfile
import subprocess
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmark import compare, measure


class BenchmarkTest(unittest.TestCase):
    def test_small_absolute_time_variation_is_not_a_regression(self):
        self.assertEqual([], compare({'medianWallSeconds': 1.6, 'medianPeakRssMiB': 200},
                                     {'medianWallSeconds': 1, 'medianPeakRssMiB': 200})['failures'])

    def test_substantial_time_and_memory_regressions_fail(self):
        result = compare({'medianWallSeconds': 1.9, 'medianPeakRssMiB': 270},
                         {'medianWallSeconds': 1, 'medianPeakRssMiB': 200})
        self.assertEqual(2, len(result['failures']))

    def test_small_relative_memory_variation_is_not_a_regression(self):
        self.assertEqual([], compare({'medianWallSeconds': 2, 'medianPeakRssMiB': 240},
                                     {'medianWallSeconds': 2, 'medianPeakRssMiB': 200})['failures'])

    def test_invalid_metrics_cannot_bypass_the_gate(self):
        for value in (math.nan, math.inf, -1, 0):
            with self.subTest(value=value), self.assertRaises(ValueError):
                compare({'medianWallSeconds': value, 'medianPeakRssMiB': 200},
                        {'medianWallSeconds': 1, 'medianPeakRssMiB': 200})
            with self.subTest(reference=value), self.assertRaises(ValueError):
                compare({'medianWallSeconds': 1, 'medianPeakRssMiB': 200},
                        {'medianWallSeconds': value, 'medianPeakRssMiB': 200})

    @unittest.skipUnless(sys.platform in ('linux', 'darwin'), 'benchmark runner requires Linux/macOS')
    def test_input_failure_persists_a_failed_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / 'report.json'
            process = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1] / 'benchmark.py'),
                                      '--jar', str(root / 'missing.jar'), '--output', str(output)], capture_output=True)
            self.assertNotEqual(0, process.returncode)
            result = json.loads(output.read_text())
            self.assertFalse(result['passed'])
            self.assertIn('FileNotFoundError', result['error'])

    @unittest.skipUnless(sys.platform in ('linux', 'darwin'), 'wait4 peak RSS requires Linux/macOS')
    def test_later_small_process_does_not_inherit_an_earlier_peak(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            large = measure([sys.executable, '-c', 'a=bytearray(64*1024*1024); a[::4096]=bytes([1])*len(a[::4096])'],
                            root / 'large.out', root / 'large.err', 10)
            small = measure([sys.executable, '-c', 'print("small")'], root / 'small.out', root / 'small.err', 10)
            self.assertEqual(0, large['exitCode'])
            self.assertEqual(0, small['exitCode'])
            self.assertGreater(large['peakRssMiB'], small['peakRssMiB'] + 32)

    @unittest.skipUnless(sys.platform in ('linux', 'darwin'), 'wait4 timeout requires Linux/macOS')
    def test_timeout_kills_and_reaps_the_process(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(TimeoutError):
                measure([sys.executable, '-c', 'import time; time.sleep(10)'], root / 'out', root / 'err', 0.05)


if __name__ == '__main__':
    unittest.main()
