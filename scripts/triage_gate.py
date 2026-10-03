"""Validate findings and retain every reviewed error in pinned corpus scans."""

class GateError(ValueError):
    pass


def identity(repository, commit, finding, column=True):
    return (repository, commit, finding['file'], finding['line'],
            finding.get('column') if column else None, finding['checkId'], finding['reference'])


def validate(report, triage, manifest):
    pins = {entry['repository']: entry['commit'] for entry in manifest['repositories']}
    if len(pins) != len(manifest['repositories']):
        raise GateError('Duplicate corpus repository pins')
    decisions = {}
    for decision in triage['findings']:
        repo, commit = decision['repository'], decision['commit']
        if pins.get(repo) != commit:
            raise GateError(f'Triage commit does not match corpus pin: {repo}@{commit}')
        if decision['classification'] not in ('true-positive', 'false-positive'):
            raise GateError(f'Unreviewed triage classification: {decision}')
        if not (decision.get('evidence') or decision.get('reason') or '').strip():
            raise GateError(f'Triage requires review evidence: {decision}')
        key = identity(repo, commit, decision)
        if key in decisions:
            raise GateError(f'Duplicate triage identity: {key}')
        decisions[key] = decision
    seen, actual, repositories, unreviewed = set(), set(), set(), []
    false, count = 0, 0
    for run in report['runs']:
        repo, commit = run['repository'], run['commit']
        if repo in repositories or pins.get(repo) != commit:
            raise GateError(f'Duplicate run or mismatched corpus pin: {repo}@{commit}')
        repositories.add(repo)
        if 'error' in run or run.get('exitCode') not in (0, 1) or 'result' not in run:
            raise GateError(f'Corpus input failure: {repo}: {run.get("error", run.get("exitCode"))}')
        for finding in run['result']['findings']:
            key = identity(repo, commit, finding)
            if key in actual:
                raise GateError(f'Duplicate reported finding: {key}')
            actual.add(key)
            # Older triage records omit columns; current expanded records include them.
            reviewed = key if key in decisions else identity(repo, commit, finding, column=False)
            decision = decisions.get(reviewed)
            count += 1
            if decision is None:
                unreviewed.append(key)
            else:
                seen.add(reviewed)
                false += decision['classification'] == 'false-positive'
    if repositories != set(pins):
        raise GateError(f'Missing corpus runs: {sorted(set(pins) - repositories)}')
    missing = [key for key, decision in decisions.items()
               if decision['classification'] == 'true-positive' and key not in seen]
    problems = []
    if unreviewed:
        problems.append(f'Unreviewed findings: {unreviewed}')
    if missing:
        problems.append(f'Known errors disappeared: {missing}')
    if count and false / count >= 0.05:
        problems.append(f'False-positive fraction is not below 5%: {false}/{count}')
    if problems:
        raise GateError('\n'.join(problems))
    return {'findings': count, 'falsePositives': false,
            'knownErrorsPreserved': sum(d['classification'] == 'true-positive' for d in decisions.values())}
