#!/usr/bin/env bash
# Guard: every `registerwerk_*` metric name referenced by an alert rule, a promtool test, a Grafana
# dashboard or the Helm chart must resolve to a Micrometer meter that the backend really registers.
#
# Why: alert names were written by hand against meter names that did not exist (or against
# Prometheus names that Micrometer never exposes - e.g. a Gauge registered as `x_total` is exposed
# as `x`, so `x_total > 0` can never fire). Such an alert is silent exactly when it matters.
#
# How: scans backend/src/main/java for every "registerwerk.some.name" / "registerwerk_some_name"
# string literal, works out the meter kind from the registration call on (or just above) that
# line, and derives the names Micrometer's Prometheus registry exposes for it:
#   counter  x      -> x_total                           (a trailing _total in the literal is folded)
#   gauge    x(_total) -> x                              (Prometheus drops _total on non-counters)
#   timer    x      -> x_seconds, _seconds_count/_sum/_max, + _seconds_bucket when a histogram is
#                      configured (publishPercentileHistogram / SLO in code, or
#                      management.metrics.distribution.percentiles-histogram in application.yml)
#   summary  x      -> x, x_count/_sum/_max (+ _bucket likewise)
# A literal whose kind cannot be told is treated as "any form" (reported with -v).
#
# Usage: scripts/check-alert-metrics.sh [-v] [--list]     (run from anywhere; exit 1 on any miss)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 -I - "$ROOT" "$@" <<'PYEOF'
import difflib, os, re, sys

root = sys.argv[1]
verbose = '-v' in sys.argv[2:]
list_only = '--list' in sys.argv[2:]

SRC = os.path.join(root, 'backend/src/main/java')
APP_YML = os.path.join(root, 'backend/src/main/resources/application.yml')
SCAN_DIRS = ['monitoring', 'deploy']
SCAN_EXT = ('.yml', '.yaml', '.json', '.tpl', '.txt', '.sh')

# Tokens that look like metric names but are not: compose network, DB role, Pushgateway job names.
NOT_METRICS = {
    'registerwerk_app', 'registerwerk_default', 'registerwerk_pg_backup', 'registerwerk_wallets_backup',
}

LITERAL = re.compile(r'"(registerwerk[._][A-Za-z0-9_.]+)"')
KINDS = [  # (kind, same-line / preceding-line pattern); first match on the line wins
    ('timer', re.compile(r'Timer\.builder|\.timer\(|\bTimer\b')),
    ('summary', re.compile(r'DistributionSummary|\.summary\(')),
    ('counter', re.compile(r'Counter\.builder|\.counter\(|\bCounter\b|FunctionCounter')),
    ('gauge', re.compile(r'Gauge\.builder|\.gauge\(|\bgauge\(|MultiGauge|TimeGauge|SweepDriftCounter|\bGauge\b')),
]
HISTOGRAM_HINT = re.compile(r'publishPercentileHistogram|serviceLevelObjectives|publishPercentiles|\.sla\(')


def norm(name):
    return name.replace('.', '_')


def kind_of(lines, i):
    for j in (i, i - 1, i - 2, i - 3):
        if j < 0:
            break
        for kind, pat in KINDS:
            if pat.search(lines[j]):
                return kind
    return 'any'


# application.yml: timers with a configured histogram (e.g. registerwerk.scheduled.job: true)
histogram_cfg = set()
if os.path.exists(APP_YML):
    in_block = False
    for line in open(APP_YML, encoding='utf-8'):
        if re.match(r'\s+percentiles-histogram:', line):
            in_block = True
            continue
        if in_block:
            m = re.match(r'\s+([A-Za-z0-9_.]+):\s*true\b', line)
            if m:
                histogram_cfg.add(norm(m.group(1)))
            elif line.strip() and not line.strip().startswith('#'):
                in_block = False

exposed = {}     # exposed prometheus name -> "kind literal (file:line)"
wildcards = []   # (norm, origin) for literals of unknown kind
for dirpath, _, files in os.walk(SRC):
    for f in files:
        if not f.endswith('.java'):
            continue
        path = os.path.join(dirpath, f)
        lines = open(path, encoding='utf-8').read().split('\n')
        for i, line in enumerate(lines):
            for m in LITERAL.finditer(line):
                lit = m.group(1)
                n = norm(lit)
                origin = '%s %s (%s:%d)' % (kind_of(lines, i), lit, os.path.relpath(path, root), i + 1)
                kind = kind_of(lines, i)
                if kind == 'counter':
                    exposed[n.removesuffix('_total') + '_total'] = origin
                elif kind == 'gauge':
                    exposed[n.removesuffix('_total')] = origin
                elif kind in ('timer', 'summary'):
                    base = n if kind == 'summary' else (n if n.endswith('_seconds') else n + '_seconds')
                    names = [base, base + '_count', base + '_sum', base + '_max']
                    window = '\n'.join(lines[i:i + 10])
                    if n in histogram_cfg or HISTOGRAM_HINT.search(window):
                        names.append(base + '_bucket')
                    for x in names:
                        exposed[x] = origin
                elif '_' in lit:
                    # underscore form, kind unknown: accept in any exposed form. Dotted literals of
                    # unknown kind are @ConfigurationProperties prefixes / log text, not meters.
                    wildcards.append((n, origin))

if list_only:
    for k in sorted(exposed):
        print(k, '<-', exposed[k])
    for n, o in wildcards:
        print(n, '(any form) <-', o)
    sys.exit(0)

TOKEN = re.compile(r'registerwerk_[a-z0-9_]+')


def resolves(token):
    if token in NOT_METRICS:
        return True
    if token.endswith('_'):          # prefix reference such as registerwerk_chaincache_stream_*
        return any(k.startswith(token) for k in exposed) or any(n.startswith(token) for n, _ in wildcards)
    if token in exposed:
        return True
    for n, _ in wildcards:           # unknown kind: accept the literal in any exposed form
        if token == n or re.fullmatch(re.escape(n) + r'(_total|_seconds|_seconds_count|_seconds_sum|_seconds_max'
                                      r'|_seconds_bucket|_count|_sum|_max|_bucket)?', token):
            return True
    return False


problems = []
checked = set()
for scan in SCAN_DIRS:
    for dirpath, _, files in os.walk(os.path.join(root, scan)):
        if any(p in dirpath for p in ('node_modules', '/target', '/.git')):
            continue
        for f in files:
            if not f.endswith(SCAN_EXT):
                continue
            path = os.path.join(dirpath, f)
            try:
                text = open(path, encoding='utf-8').read()
            except UnicodeDecodeError:
                continue
            for ln, line in enumerate(text.split('\n'), 1):
                for m in TOKEN.finditer(line):
                    tok = m.group(0)
                    checked.add(tok)
                    if not resolves(tok):
                        near = difflib.get_close_matches(tok, list(exposed), n=2, cutoff=0.8)
                        problems.append('%s:%d: %s%s' % (os.path.relpath(path, root), ln, tok,
                                        ('   (closest registered: %s)' % ', '.join(near)) if near else ''))

if verbose and wildcards:
    print('note: %d meter literal(s) of undetermined kind are matched in any exposed form:' % len(wildcards))
    for n, o in wildcards:
        print('  ' + o)

if problems:
    print('FAIL: %d reference(s) to a registerwerk_* metric no backend meter exposes:' % len(problems))
    for p in problems:
        print('  ' + p)
    print('\nA gauge named x_total is exposed as x; a counter x is exposed as x_total; a timer x as '
          'x_seconds_count/_sum/_bucket. See the header of scripts/check-alert-metrics.sh.')
    sys.exit(1)
print('OK: %d distinct registerwerk_* names referenced by monitoring/ and deploy/ all resolve to a '
      'registered meter (%d exposed names derived from the backend source).' % (len(checked), len(exposed)))
PYEOF
