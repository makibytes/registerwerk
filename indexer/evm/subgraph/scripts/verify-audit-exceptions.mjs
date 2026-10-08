import { spawnSync } from 'node:child_process';

const audit = spawnSync('npm', ['audit', '--json'], {
  cwd: new URL('..', import.meta.url),
  encoding: 'utf8',
});

if (!audit.stdout) {
  console.error(audit.stderr || 'npm audit returned no JSON output');
  process.exit(1);
}

let report;
try {
  report = JSON.parse(audit.stdout);
} catch (error) {
  console.error('Could not parse npm audit output:', error);
  process.exit(1);
}

// npm reports its own failures (registry unreachable, bad config, ...) as {"error": {...}} with no
// `vulnerabilities` key; that must not be read as "nothing to report".
if (!report.vulnerabilities) {
  console.error('npm audit did not return a vulnerability report:', JSON.stringify(report.error ?? report));
  process.exit(1);
}

// Accepted exception: advisories that only exist inside the dependency tree of the Graph CLI
// (a devDependency used for `graph codegen` / `graph build` on trusted local input; none of it is
// shipped or runs at indexing time) AND for which no patched release has been published, so an
// `overrides` entry cannot clear them:
//   - decompress (latest 4.2.1 is still affected)
//   - braces (latest 3.0.3 is still affected) <- micromatch <- fast-glob <- it-glob
//     <- kubo-rpc-client
//   - jayson pins stream-json ^1.x; the patched stream-json 3.x is two majors away
// Everything that can be fixed is fixed through `overrides` in package.json (axios, js-yaml,
// undici, ...) or by the lockfile; anything outside this set fails the build.
const expectedPackages = new Set([
  '@graphprotocol/graph-cli',
  'braces',
  'decompress',
  'fast-glob',
  'it-glob',
  'jayson',
  'kubo-rpc-client',
  'micromatch',
  'stream-json',
]);
const actualPackages = Object.keys(report.vulnerabilities);
const unexpectedPackages = actualPackages.filter((name) => !expectedPackages.has(name));
const missingPackages = [...expectedPackages].filter((name) => !actualPackages.includes(name));

const sameMembers = (actual, expected) =>
  Array.isArray(actual) && actual.length === expected.length && expected.every((name) => actual.includes(name));
const vulnerabilities = report.vulnerabilities;
const graphCli = vulnerabilities['@graphprotocol/graph-cli'];

// The shape pins *why* each package is flagged: the Graph CLI is the only direct dependency
// involved, and every other excepted package is reached through it.
const expectedShape =
  graphCli?.isDirect === true &&
  sameMembers(graphCli.via, ['decompress', 'jayson', 'kubo-rpc-client']) &&
  [...expectedPackages]
    .filter((name) => name !== '@graphprotocol/graph-cli')
    .every((name) => vulnerabilities[name]?.isDirect === false) &&
  sameMembers(vulnerabilities.decompress?.effects, ['@graphprotocol/graph-cli']) &&
  sameMembers(vulnerabilities.jayson?.effects, ['@graphprotocol/graph-cli']) &&
  sameMembers(vulnerabilities['kubo-rpc-client']?.effects, ['@graphprotocol/graph-cli']) &&
  sameMembers(vulnerabilities['stream-json']?.effects, ['jayson']) &&
  sameMembers(vulnerabilities.braces?.effects, ['micromatch']) &&
  sameMembers(vulnerabilities.micromatch?.effects, ['fast-glob']) &&
  sameMembers(vulnerabilities['fast-glob']?.effects, ['it-glob']) &&
  sameMembers(vulnerabilities['it-glob']?.effects, ['kubo-rpc-client']);

if (unexpectedPackages.length || missingPackages.length || !expectedShape) {
  console.error('The Graph CLI advisory set changed; review it before updating the exception.');
  console.error(JSON.stringify({ actualPackages, unexpectedPackages, missingPackages }, null, 2));
  process.exit(1);
}

console.log('Verified isolated Graph CLI build-tool advisory exception.');
