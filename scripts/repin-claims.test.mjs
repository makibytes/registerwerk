#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { applyRepin, commandsToRun, planRepin, repinClaims } from "./repin-claims.mjs";
import { recordDigest, sha256, verifyClaims } from "./verify-claims.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const sourceRepo = path.resolve(here, "..");

/** A temporary copy of the real registry plus every file it references, so the real shape is exercised. */
function copyOfRegistry() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "registerwerk-repin-"));
  const copy = relative => {
    const target = path.join(root, relative);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(path.join(sourceRepo, relative), target);
  };
  for (const relative of [
    "docs/claims/registry.json", "docs/claims/registry.schema.json", "docs/claims/registry.history.json",
    "docs/claims/suppressions.json", ".github/workflows/claims.yml"
  ]) copy(relative);
  const registry = JSON.parse(fs.readFileSync(path.join(root, "docs/claims/registry.json"), "utf8"));
  for (const claim of registry.claims) {
    copy(claim.path);
    for (const evidence of claim.evidence) if (evidence.type !== "OFFICIAL") copy(evidence.reference);
  }
  return { root, registry };
}

const readRegistry = root => JSON.parse(fs.readFileSync(path.join(root, "docs/claims/registry.json"), "utf8"));
const asOf = "2026-10-10";

// A repository whose pins are current has nothing to re-pin.
{
  const { root } = copyOfRegistry();
  const { changes } = planRepin(root);
  assert.deepEqual(changes, [], "the committed registry must have current pins (run repin-claims.mjs --write)");
  assert.deepEqual(repinClaims({ repo: root, write: true }), { changes: [], written: false, ran: [] });
}

// Editing a pinned file is reported, and --write renews exactly that pin, its claim's record hash and the revision.
{
  const { root, registry: before } = copyOfRegistry();
  const target = before.claims[0].evidence[0].reference;
  fs.appendFileSync(path.join(root, target), "\n// edited\n");

  assert.ok(verifyClaims({ repo: root, asOf }).errors.some(error =>
    error.includes("evidence SHA-256 mismatch") && error.includes(target) && error.includes("repin-claims.mjs")),
  "the verifier names the file and the re-pin command");

  const dryRun = repinClaims({ repo: root });
  assert.equal(dryRun.written, false);
  assert.equal(dryRun.changes.length, 1);
  assert.deepEqual(readRegistry(root), before, "a dry run must not write");

  const result = repinClaims({ repo: root, write: true });
  assert.equal(result.written, true);
  const after = readRegistry(root);
  assert.equal(after.registryRevision, before.registryRevision + 1);
  const [claimBefore, claimAfter] = [before.claims[0], after.claims[0]];
  assert.equal(claimAfter.evidence[0].sha256, sha256(fs.readFileSync(path.join(root, target))));
  assert.equal(claimAfter.recordSha256, recordDigest(claimAfter));
  assert.notEqual(claimAfter.recordSha256, claimBefore.recordSha256);
  // Everything except the renewed pin, record hash and revision is untouched, claim by claim.
  const { recordSha256: _r1, evidence: ev1, ...rest1 } = claimBefore;
  const { recordSha256: _r2, evidence: ev2, ...rest2 } = claimAfter;
  assert.deepEqual(rest2, rest1);
  assert.deepEqual(ev2.slice(1), ev1.slice(1));
  assert.deepEqual(after.claims.slice(1), before.claims.slice(1));
  assert.deepEqual(verifyClaims({ repo: root, asOf }).errors, [], "re-pinned registry verifies cleanly");

  // The formatting is stable, so the diff of a re-pin shows only the renewed values.
  const text = fs.readFileSync(path.join(root, "docs/claims/registry.json"), "utf8");
  assert.equal(text, `${JSON.stringify(after, null, 2)}\n`);
}

// Other edits stay visible: a hand-edited claim that is not re-pinned keeps failing its record hash.
{
  const { root } = copyOfRegistry();
  const registry = readRegistry(root);
  registry.claims[1].scope = `${registry.claims[1].scope} (silently widened)`;
  fs.writeFileSync(path.join(root, "docs/claims/registry.json"), JSON.stringify(registry, null, 2));
  fs.appendFileSync(path.join(root, registry.claims[0].evidence[0].reference), "\n// edited\n");
  repinClaims({ repo: root, write: true });
  const errors = verifyClaims({ repo: root, asOf }).errors;
  assert.ok(errors.some(error => error.startsWith(`${registry.claims[1].id}: canonical record SHA-256 mismatch`)),
    "repin must not repair a record hash it did not touch");
}

// --run: the evidence commands of the affected claim (all its TEST entries) must pass before anything is written.
{
  const { root, registry } = copyOfRegistry();
  const claim = registry.claims[0];
  fs.appendFileSync(path.join(root, claim.evidence[0].reference), "\n// edited\n");
  const expected = [...new Set(claim.evidence.filter(e => e.ciCommandId).map(e => e.ciCommandId))];
  assert.deepEqual(commandsToRun(registry, planRepin(root).changes), expected);

  const calls = [];
  assert.throws(() => repinClaims({ repo: root, write: true, run: true, runner: id => { calls.push(id); return 1; } }),
    /failed .* nothing was re-pinned/);
  assert.deepEqual(readRegistry(root), registry, "a failing evidence command must leave the registry untouched");

  const passed = repinClaims({ repo: root, write: true, run: true, runner: id => { calls.push(id); return 0; } });
  assert.equal(passed.written, true);
  assert.deepEqual(passed.ran.map(r => r.commandId), expected);
  assert.deepEqual(verifyClaims({ repo: root, asOf }).errors, []);
}

// A missing or escaping evidence reference cannot be re-pinned.
{
  const { root, registry } = copyOfRegistry();
  fs.rmSync(path.join(root, registry.claims[0].evidence[0].reference));
  assert.throws(() => planRepin(root), /expected an existing regular file/);
}

// applyRepin is a no-op without changes (no revision bump).
{
  const { registry } = copyOfRegistry();
  const revision = registry.registryRevision;
  assert.deepEqual(applyRepin(registry, []), []);
  assert.equal(registry.registryRevision, revision);
}

// CLI: dry run exits 1 on stale pins, --write exits 0, a clean tree exits 0, bad arguments exit 2.
{
  const { root, registry } = copyOfRegistry();
  const cli = (...args) => spawnSync(process.execPath, [path.join(here, "repin-claims.mjs"), "--repo", root, ...args],
    { encoding: "utf8" });
  assert.equal(cli().status, 0);
  fs.appendFileSync(path.join(root, registry.claims[0].evidence[0].reference), "\n// edited\n");
  const dry = cli();
  assert.equal(dry.status, 1);
  assert.match(dry.stdout, /Stale pins/);
  const written = cli("--write");
  assert.equal(written.status, 0);
  assert.match(written.stdout, /registryRevision is now/);
  assert.equal(cli().status, 0);
  assert.equal(cli("--bogus").status, 2);
  assert.equal(spawnSync(process.execPath, [path.join(here, "repin-claims.mjs"), "--repo"], { encoding: "utf8" }).status, 2);
}

process.stdout.write("repin-claims tests passed\n");
