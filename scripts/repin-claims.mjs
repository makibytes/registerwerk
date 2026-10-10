#!/usr/bin/env node
// Re-pins the SHA-256 of claim evidence files in docs/claims/registry.json after the files changed.
//
// The claims verifier (scripts/verify-claims.mjs) pins every REPOSITORY/TEST/DECISION evidence file by
// its complete SHA-256, so editing such a file turns "Claims governance" red until the pin is renewed.
// Renewing is deliberately a reviewed act (docs/claims/README.md): the evidence command has to pass on
// the new file first, and the repository owner approves the registry diff. This script only removes the
// manual hashing:
//
//   node scripts/repin-claims.mjs                  dry run: list stale pins (exit 1 if there are any)
//   node scripts/repin-claims.mjs --write          rewrite the pins, recordSha256 of the affected
//                                                  claims, and bump registryRevision
//   node scripts/repin-claims.mjs --write --run    first run the CI evidence commands of the affected
//                                                  claims; re-pin only if every one of them passes
//
// It never touches a statement, status, reviewer, date or limitation, and it does not "repair"
// recordSha256 of claims whose evidence did not change, so any other edit stays visible to the verifier.

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { recordDigest, resolveRepositoryFile, sha256 } from "./verify-claims.mjs";
import { runEvidence } from "./run-claim-evidence.mjs";

const scriptPath = fileURLToPath(import.meta.url);
const defaultRepo = path.resolve(path.dirname(scriptPath), "..");

/**
 * @returns {{registry: object, changes: Array<{claimId: string, index: number, type: string,
 *   reference: string, ciCommandId?: string, from: string, to: string}>}}
 */
export function planRepin(repo) {
  const registry = JSON.parse(fs.readFileSync(path.join(repo, "docs/claims/registry.json"), "utf8"));
  const changes = [];
  const errors = [];
  for (const claim of registry.claims ?? []) {
    for (const [index, evidence] of (claim.evidence ?? []).entries()) {
      if (evidence.type === "OFFICIAL") continue;
      const location = `${claim.id}.evidence[${index}]`;
      const file = resolveRepositoryFile(repo, evidence.reference, location, errors);
      if (!file) continue;
      const to = sha256(fs.readFileSync(file));
      if (to !== evidence.sha256) {
        changes.push({
          claimId: claim.id, index, type: evidence.type, reference: evidence.reference,
          ciCommandId: evidence.ciCommandId, from: evidence.sha256, to
        });
      }
    }
  }
  if (errors.length) throw new Error(`cannot re-pin:\n- ${errors.join("\n- ")}`);
  return { registry, changes };
}

/** CI commands that prove the claims whose evidence changed (every TEST entry of an affected claim). */
export function commandsToRun(registry, changes) {
  const affected = new Set(changes.map(change => change.claimId));
  const commands = new Set();
  for (const claim of registry.claims ?? []) {
    if (!affected.has(claim.id)) continue;
    for (const evidence of claim.evidence ?? []) if (evidence.ciCommandId) commands.add(evidence.ciCommandId);
  }
  return [...commands];
}

/** Applies the planned pins to `registry` in place; returns the ids of the claims it touched. */
export function applyRepin(registry, changes) {
  if (!changes.length) return [];
  const touched = new Set();
  for (const change of changes) {
    const claim = registry.claims.find(candidate => candidate.id === change.claimId);
    claim.evidence[change.index].sha256 = change.to;
    touched.add(claim.id);
  }
  for (const claim of registry.claims) if (touched.has(claim.id)) claim.recordSha256 = recordDigest(claim);
  registry.registryRevision += 1;
  return [...touched];
}

/**
 * @param {{repo?: string, write?: boolean, run?: boolean, runner?: (id: string) => number}} options
 *        `runner` is injectable for tests; it returns the exit status of an evidence command.
 */
export function repinClaims({ repo = defaultRepo, write = false, run = false, runner = runEvidence } = {}) {
  const { registry, changes } = planRepin(repo);
  if (!changes.length) return { changes, written: false, ran: [] };
  const ran = [];
  if (run) {
    for (const commandId of commandsToRun(registry, changes)) {
      const status = runner(commandId);
      ran.push({ commandId, status });
      if (status !== 0) {
        throw new Error(`evidence command ${commandId} failed (exit ${status}); nothing was re-pinned`);
      }
    }
  }
  if (!write) return { changes, written: false, ran };
  applyRepin(registry, changes);
  fs.writeFileSync(path.join(repo, "docs/claims/registry.json"), `${JSON.stringify(registry, null, 2)}\n`);
  return { changes, written: true, ran, registryRevision: registry.registryRevision };
}

function main(argv) {
  const known = new Set(["--write", "--run", "--repo"]);
  const repoFlag = argv.indexOf("--repo");
  const repo = repoFlag >= 0 ? path.resolve(argv[repoFlag + 1] ?? "") : defaultRepo;
  const unknown = argv.filter((arg, i) => arg.startsWith("-") ? !known.has(arg) : argv[i - 1] !== "--repo");
  if (unknown.length || (repoFlag >= 0 && !argv[repoFlag + 1])) {
    process.stderr.write(`usage: node scripts/repin-claims.mjs [--write] [--run] [--repo <dir>]\n`);
    return 2;
  }
  const write = argv.includes("--write");
  const run = argv.includes("--run");
  let result;
  try {
    result = repinClaims({ repo, write, run });
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    return 1;
  }
  if (!result.changes.length) {
    process.stdout.write("All claim evidence pins are current.\n");
    return 0;
  }
  for (const change of result.changes) {
    process.stdout.write(`${change.claimId} ${change.type} ${change.reference}\n`
      + `    ${change.from.slice(0, 12)}… -> ${change.to.slice(0, 12)}…\n`);
  }
  if (!result.written) {
    process.stdout.write("\nStale pins (dry run, nothing written). Run the evidence command(s) for the affected claims,\n"
      + "then `node scripts/repin-claims.mjs --write` (or `--write --run` to do both).\n");
    return 1;
  }
  process.stdout.write(`\nRe-pinned ${result.changes.length} evidence file(s); registryRevision is now ${result.registryRevision}.\n`
    + "Statements, statuses, reviewers and dates were not changed and this is not a review: the repository\n"
    + "owner still approves the docs/claims/registry.json diff (docs/claims/README.md).\n");
  return 0;
}

if (process.argv[1] && path.resolve(process.argv[1]) === scriptPath) {
  process.exitCode = main(process.argv.slice(2));
}
