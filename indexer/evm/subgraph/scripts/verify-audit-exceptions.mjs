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

const expectedPackages = new Set(['@graphprotocol/graph-cli', 'decompress']);
const actualPackages = Object.keys(report.vulnerabilities ?? {});
const unexpectedPackages = actualPackages.filter((name) => !expectedPackages.has(name));
const missingPackages = [...expectedPackages].filter((name) => !actualPackages.includes(name));

const graphCli = report.vulnerabilities?.['@graphprotocol/graph-cli'];
const decompress = report.vulnerabilities?.decompress;
const expectedShape =
  graphCli?.isDirect === true &&
  graphCli?.via?.length === 1 &&
  graphCli.via[0] === 'decompress' &&
  decompress?.isDirect === false &&
  decompress?.effects?.length === 1 &&
  decompress.effects[0] === '@graphprotocol/graph-cli';

if (unexpectedPackages.length || missingPackages.length || !expectedShape) {
  console.error('The Graph CLI advisory set changed; review it before updating the exception.');
  console.error(JSON.stringify({ actualPackages, unexpectedPackages, missingPackages }, null, 2));
  process.exit(1);
}

console.log('Verified isolated Graph CLI/decompress build-tool advisory exception.');
