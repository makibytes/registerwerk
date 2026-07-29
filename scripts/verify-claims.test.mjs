#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { compareHistoricalState, recordDigest, scanText, sha256, verifyClaims, workflowEvidenceErrors } from "./verify-claims.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const sourceRepo = path.resolve(here, "..");
const fixtures = JSON.parse(fs.readFileSync(path.join(here, "claims-scan-fixtures.json"), "utf8"));

for (const [index, text] of fixtures.unsafe.entries()) {
  assert.ok(scanText(`unsafe-${index}.md`, text).length > 0, `unsafe fixture passed: ${text}`);
}
for (const [index, text] of fixtures.qualified.entries()) {
  assert.equal(scanText(`qualified-${index}.md`, text).length, 0, `qualified fixture failed: ${text}`);
}

const falseClaim = {
  path: "claim.md",
  statement: "Registerwerk is fully compliant with eWpG in Germany.",
  category: "LEGAL",
  status: "FALSE_RETIRED"
};
assert.ok(scanText("claim.md", falseClaim.statement, [falseClaim]).length > 0,
  "FALSE_RETIRED must not exempt positive wording");
const conditionalClaim = { ...falseClaim, status: "CONDITIONAL" };
assert.ok(scanText("claim.md", conditionalClaim.statement, [conditionalClaim]).length > 0,
  "CONDITIONAL must not exempt positive wording");
const selfApprovedLegalClaim = { ...falseClaim, status: "EXTERNALLY_APPROVED" };
assert.ok(scanText("claim.md", selfApprovedLegalClaim.statement, [selfApprovedLegalClaim]).length > 0,
  "disabled EXTERNALLY_APPROVED must not exempt positive wording");

function write(root, relative, content) {
  const target = path.join(root, relative);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, content);
}

function fixtureRepository() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "registerwerk-claims-"));
  const statement = "A narrow technical property holds for this controlled test fixture.";
  write(root, "docs/claims/README.md", statement);
  write(root, "evidence.txt", "pinned evidence\n");
  write(root, "docs/claims/registry.schema.json", fs.readFileSync(path.join(sourceRepo, "docs/claims/registry.schema.json")));
  write(root, "docs/claims/registry.history.json", JSON.stringify({
    schemaVersion: 1,
    entries: [{ id: "RW-CLAIM-9999", firstRegisteredAt: "2026-07-01" }]
  }));
  write(root, "docs/claims/suppressions.json", fs.readFileSync(path.join(sourceRepo, "docs/claims/suppressions.json")));
  for (const migration of ["V1__initial_schema.sql", "V12__payment_rail_micar_verification.sql"]) {
    write(root, `backend/src/main/resources/db/migration/${migration}`,
      fs.readFileSync(path.join(sourceRepo, `backend/src/main/resources/db/migration/${migration}`)));
  }
  write(root, ".github/workflows/claims.yml", [
    "jobs:",
    "  contract-evidence:",
    "    steps:",
    "      - run: node scripts/run-claim-evidence.mjs contract-erc3525",
    "  backend-evidence:",
    "    steps:",
    "      - run: node scripts/run-claim-evidence.mjs backend-regreporting",
    "  indexer-evidence:",
    "    steps:",
    "      - run: node scripts/run-claim-evidence.mjs indexer-subgraph"
  ].join("\n"));
  const claim = {
    id: "RW-CLAIM-9999",
    statement,
    path: "docs/claims/README.md",
    textSha256: sha256(statement),
    recordSha256: "0".repeat(64),
    category: "SETTLEMENT",
    status: "VERIFIED_TECHNICAL",
    scope: "Test fixture scope only.",
    owner: "Test owner",
    reviewedBy: "Independent test reviewer",
    reviewedAt: "2026-07-01",
    expiresAt: "2026-10-01",
    evidence: [{
      type: "TEST",
      reference: "evidence.txt",
      sha256: sha256("pinned evidence\n"),
      ciCommandId: "contract-erc3525"
    }],
    limitations: ["This is only a verifier test fixture."]
  };
  claim.recordSha256 = recordDigest(claim);
  const registry = {
    $schema: "./registry.schema.json",
    schemaVersion: 1,
    registryRevision: 1,
    generatedAt: "2026-07-29",
    historyPath: "docs/claims/registry.history.json",
    suppressionsPath: "docs/claims/suppressions.json",
    externalApprovalEnabled: false,
    claims: [claim]
  };
  write(root, "docs/claims/registry.json", JSON.stringify(registry));
  return { root, registry, claim };
}

function withMutation(mutator) {
  const fixture = fixtureRepository();
  mutator(fixture);
  fixture.claim.recordSha256 = recordDigest(fixture.claim);
  write(fixture.root, "docs/claims/registry.json", JSON.stringify(fixture.registry));
  return verifyClaims({ repo: fixture.root, asOf: "2026-07-29" }).errors;
}

{
  const fixture = fixtureRepository();
  assert.deepEqual(verifyClaims({ repo: fixture.root, asOf: "2026-07-29" }).errors, []);
}
assert.ok(withMutation(({ claim }) => { claim.expiresAt = "2026-02-30"; })
  .some(error => error.includes("not a real date")));
assert.ok(withMutation(({ claim }) => { claim.expiresAt = "2027-07-01"; })
  .some(error => error.includes("review horizon")));
assert.ok(withMutation(({ claim }) => { claim.evidence[0].reference = "../../etc/passwd"; })
  .some(error => error.includes("escapes the repository")));
assert.ok(withMutation(({ claim }) => { claim.evidence[0].sha256 = "f".repeat(64); })
  .some(error => error.includes("evidence SHA-256 mismatch")));
assert.ok(withMutation(({ registry }) => { registry.unexpectedPolicy = true; })
  .some(error => error.includes("unexpected unexpectedPolicy")));
assert.ok(withMutation(({ registry }) => { registry.claims.length = 0; })
  .some(error => error.includes("claim was deleted")));
assert.ok(withMutation(({ claim }) => { claim.evidence = [{ type: "REPOSITORY", reference: "evidence.txt", sha256: sha256("pinned evidence\n") }]; })
  .some(error => error.includes("requires valid pinned TEST evidence")));
assert.ok(withMutation(({ root }) => { write(root, "docs/claims/registry.schema.json", "{}\n"); })
  .some(error => error.includes("schema content differs")));
assert.ok(withMutation(({ root }) => {
  const workflow = fs.readFileSync(path.join(root, ".github/workflows/claims.yml"), "utf8");
  write(root, ".github/workflows/claims.yml", workflow.replace(
    "      - run: node scripts/run-claim-evidence.mjs contract-erc3525",
    "      # - run: node scripts/run-claim-evidence.mjs contract-erc3525"));
}).some(error => error.includes("contract-erc3525; found 0")));
assert.ok(withMutation(({ root }) => {
  const workflow = fs.readFileSync(path.join(root, ".github/workflows/claims.yml"), "utf8");
  write(root, ".github/workflows/claims.yml", workflow.replace(
    "      - run: node scripts/run-claim-evidence.mjs backend-regreporting",
    "      - run: node scripts/run-claim-evidence.mjs backend-regreporting\n        if: false"));
}).some(error => error.includes("backend-regreporting must be unconditional")));
assert.ok(withMutation(({ root }) => {
  const workflow = fs.readFileSync(path.join(root, ".github/workflows/claims.yml"), "utf8");
  write(root, ".github/workflows/claims.yml", workflow.replace(
    "  indexer-evidence:\n    steps:",
    "  indexer-evidence:\n    if: false\n    steps:"));
}).some(error => error.includes("evidence job indexer-subgraph must be unconditional")));
assert.ok(withMutation(({ root }) => {
  const workflow = fs.readFileSync(path.join(root, ".github/workflows/claims.yml"), "utf8");
  write(root, ".github/workflows/claims.yml", workflow.replace(
    "  contract-evidence:\n    steps:",
    "  contract-evidence:\n    continue-on-error: true\n    steps:"));
}).some(error => error.includes("contract-erc3525 must be unconditional, independent, and gating")));
assert.ok(withMutation(({ root }) => { write(root, "oversized.md", Buffer.alloc(2_000_001, 0x61)); })
  .some(error => error.includes("exceeds 2000000 bytes")));
assert.ok(withMutation(({ root }) => { write(root, "nul.md", Buffer.from("safe\0hidden fully compliant with eWpG")); })
  .some(error => error.includes("contains a NUL byte")));
assert.ok(withMutation(({ root }) => { fs.symlinkSync("docs/claims/README.md", path.join(root, "linked.md")); })
  .some(error => error.includes("must not be a symlink")));
assert.ok(withMutation(({ root }) => {
  const document = JSON.parse(fs.readFileSync(path.join(root, "docs/claims/suppressions.json"), "utf8"));
  document.entries.push({ ...document.entries[0], id: "RW-SUP-0002" });
  write(root, "docs/claims/suppressions.json", JSON.stringify(document));
}).some(error => error.includes("not in the closed schema-version-1 allowlist")));

{
  const fixture = fixtureRepository();
  const baseRegistry = structuredClone(fixture.registry);
  const baseHistory = JSON.parse(fs.readFileSync(path.join(fixture.root, "docs/claims/registry.history.json"), "utf8"));
  const currentRegistry = { ...structuredClone(baseRegistry), registryRevision: 2, claims: [] };
  const currentHistory = { ...structuredClone(baseHistory), entries: [] };
  const errors = compareHistoricalState(currentRegistry, currentHistory, baseRegistry, baseHistory);
  assert.ok(errors.some(error => error.includes("claim present in base was removed")));
  assert.ok(errors.some(error => error.includes("history entry present in base was removed")));
}
{
  const fixture = fixtureRepository();
  const history = JSON.parse(fs.readFileSync(path.join(fixture.root, "docs/claims/registry.history.json"), "utf8"));
  const current = structuredClone(fixture.registry);
  current.claims[0].scope = "Changed without advancing revision.";
  assert.ok(compareHistoricalState(current, history, fixture.registry, history)
    .some(error => error.includes("registryRevision must increase")));
}

assert.ok(workflowEvidenceErrors("# - run: node scripts/run-claim-evidence.mjs contract-erc3525")
  .some(error => error.includes("contract-erc3525; found 0")));

{
  const result = spawnSync(process.execPath, [path.join(here, "verify-claims.mjs"), "--base-ref", "definitely-missing-ref"], {
    cwd: sourceRepo,
    encoding: "utf8",
    shell: false
  });
  assert.notEqual(result.status, 0, "unavailable Git base ref must fail closed");
  assert.match(result.stderr, /Git base reference is unavailable/);
}

process.stdout.write("Claims verifier regression tests passed.\n");
