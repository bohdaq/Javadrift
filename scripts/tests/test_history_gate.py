import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from history_gate import validate


def finding(reference, line=10):
    return dict(file='README.md', line=line, column=12, checkId='JD004', reference=reference)


class HistoryGateTest(unittest.TestCase):
    def setUp(self):
        self.target = finding('OldExtension')
        self.residual = finding('OldProcessor', 12)
        self.case = dict(methodology='natural-stale-then-doc-fix',
                         expectedRemovedNames=['OldExtension'],
                         expectedStaleFindings=[self.target, self.residual],
                         expectedFixedFindings=[self.residual])

    def test_partial_documentation_fix_retains_reviewed_error(self):
        self.assertEqual(([], []), validate(self.case, [self.target, self.residual], [self.residual]))

    def test_unrelated_new_findings_fail_on_either_revision(self):
        for stale, fixed in (([self.target, self.residual, finding('Surprise')], [self.residual]),
                             ([self.target, self.residual], [self.residual, finding('Surprise')])):
            with self.subTest(stale=stale, fixed=fixed):
                self.assertIn('unreviewed', str(validate(self.case, stale, fixed)[1]))

    def test_remaining_reviewed_error_cannot_disappear(self):
        self.assertIn('disappeared', str(validate(self.case, [self.target, self.residual], [])[1]))

    def test_substring_and_message_are_not_removed_symbol_evidence(self):
        other = finding('OldExtensionExtra')
        other['message'] = 'OldExtension was removed'
        self.assertEqual(['OldExtension'], validate(self.case, [other, self.residual], [self.residual])[0])

    def test_target_must_clear_even_if_fixed_finding_is_reviewed(self):
        self.case['expectedFixedFindings'].append(self.target)
        self.assertIn('Target remains', str(validate(self.case, [self.target, self.residual],
                                                     [self.target, self.residual])[1]))

    def test_positions_duplicates_and_missing_reviews_are_checked(self):
        self.assertIn('unreviewed', str(validate(self.case, [finding('OldExtension', 11), self.residual],
                                                  [self.residual])[1]))
        for alteration in ('duplicate', 'missing'):
            case = copy.deepcopy(self.case)
            if alteration == 'duplicate':
                case['expectedStaleFindings'].append(self.target)
            else:
                del case['expectedFixedFindings']
            with self.subTest(alteration=alteration), self.assertRaises(ValueError):
                validate(case, [self.target, self.residual], [self.residual])


if __name__ == '__main__':
    unittest.main()
