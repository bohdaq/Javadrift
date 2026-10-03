"""Require reviewed findings at both sides of a real documentation fix."""
from collections import Counter
import re

FIELDS = ('file', 'line', 'column', 'checkId', 'reference')


def identity(finding):
    return tuple(finding[field] for field in FIELDS)


def validate(case, stale, fixed):
    problems = []
    names = case['expectedRemovedNames']
    if not names or len(names) != len(set(names)):
        raise ValueError('History case requires distinct removed names')
    missing = []
    for name in names:
        pattern = re.compile(r'(?<![\w$])' + re.escape(name) + r'(?![\w$])')
        if not any(pattern.search(f['reference']) for f in stale):
            missing.append(name)
        if any(pattern.search(f['reference']) for f in fixed):
            problems.append(f'Target remains after documentation fix: {name}')
    if missing:
        problems.append(f'Missing removed targets: {missing}')
    for field, actual in (('expectedStaleFindings', stale), ('expectedFixedFindings', fixed)):
        if field not in case:
            if case['methodology'] == 'natural-stale-then-doc-fix':
                raise ValueError(f'Natural history case requires {field}')
            continue
        expected = Counter(identity(f) for f in case[field])
        if any(count != 1 for count in expected.values()):
            raise ValueError(f'Duplicate reviewed finding in {field}')
        observed = Counter(identity(f) for f in actual)
        unexpected = list((observed - expected).elements())
        disappeared = list((expected - observed).elements())
        if unexpected:
            problems.append(f'{field}: unreviewed findings: {unexpected}')
        if disappeared:
            problems.append(f'{field}: reviewed findings disappeared: {disappeared}')
    return missing, problems
