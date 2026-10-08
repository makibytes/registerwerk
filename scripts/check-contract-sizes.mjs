#!/usr/bin/env node
// Enforces the EIP-170 (24,576 B runtime) and EIP-3860 (49,152 B initcode) limits on every
// contract `forge build --sizes` reports, so nothing deployable silently outgrows a mainnet-
// grade EVM. `forge build --sizes` alone fails on ANY oversize contract; this wrapper keeps that
// strictness and adds one explicit, shrink-only exception list for known, owned debt:
//
//   - an exempted contract may not grow beyond the sizes recorded below;
//   - it must be removed from the list once it is back within the limits (a stale exemption
//     fails the build, so the list cannot rot);
//   - every contract that is not on the list fails as soon as it is over a limit.
//
// Usage: node scripts/check-contract-sizes.mjs [path-to-foundry-project (default: contracts)]

import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Known oversize contracts. `runtime`/`init` are the sizes in bytes measured when the exemption
 * was recorded; they are ceilings, not targets.
 */
export const EXEMPTIONS = {
  EwpgConfidentialFactory: {
    runtime: 27451,
    init: 27972,
    reason:
      "embeds the creation code of both ConfidentialERC20 and ConfidentialERC3643, so its runtime is "
      + "2,875 B over EIP-170 and it cannot be deployed on a chain that enforces the limit. It needs "
      + "the same split AssetTokenFactory got (coordinator plus per-token deployer modules); until then "
      + "it is a documented known limitation (docs/platform/known-limitations.md).",
  },
};

/**
 * @param {Record<string, {runtime_size:number, init_size:number, runtime_margin:number, init_margin:number}>} sizes
 *        the JSON printed by `forge build --sizes --json`
 * @param {typeof EXEMPTIONS} exemptions
 * @returns {string[]} problems; empty when the build may pass
 */
export function evaluate(sizes, exemptions = EXEMPTIONS) {
  const problems = [];
  for (const [name, size] of Object.entries(sizes)) {
    // A negative margin is the amount by which the contract is over the limit.
    const over = size.runtime_margin < 0 || size.init_margin < 0;
    const exemption = exemptions[name];
    if (!exemption) {
      if (over) {
        problems.push(
          `${name}: runtime ${size.runtime_size} B (margin ${size.runtime_margin}), initcode ${size.init_size} B `
          + `(margin ${size.init_margin}) is over the EIP-170 / EIP-3860 limit and has no exemption`,
        );
      }
      continue;
    }
    if (!over) {
      problems.push(`${name}: is within the limits again — remove it from EXEMPTIONS in scripts/check-contract-sizes.mjs`);
    } else if (size.runtime_size > exemption.runtime || size.init_size > exemption.init) {
      problems.push(
        `${name}: grew beyond its recorded exemption (runtime ${size.runtime_size} B > ${exemption.runtime} B or `
        + `initcode ${size.init_size} B > ${exemption.init} B); an exempted contract may only shrink`,
      );
    }
  }
  for (const name of Object.keys(exemptions)) {
    if (!(name in sizes)) {
      problems.push(`${name}: is exempted but forge did not report it — remove it from EXEMPTIONS`);
    }
  }
  return problems;
}

function main() {
  const here = path.dirname(fileURLToPath(import.meta.url));
  const project = path.resolve(process.argv[2] ?? path.join(here, "..", "contracts"));
  // forge exits non-zero when anything is oversize but still prints the full JSON table.
  const forge = spawnSync("forge", ["build", "--sizes", "--json"], { cwd: project, encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
  if (forge.error) {
    console.error(`could not run forge: ${forge.error.message}`);
    process.exit(1);
  }
  let sizes;
  try {
    sizes = JSON.parse(forge.stdout);
  } catch {
    console.error("forge build --sizes --json did not print a size table (compile error?):");
    console.error(forge.stderr || forge.stdout);
    process.exit(1);
  }

  const problems = evaluate(sizes);
  const exempted = Object.keys(EXEMPTIONS).filter((name) => name in sizes);
  for (const name of exempted) {
    console.log(`exempt: ${name} (${sizes[name].runtime_size} B runtime) — ${EXEMPTIONS[name].reason}`);
  }
  if (problems.length) {
    console.error(problems.map((problem) => `ERROR ${problem}`).join("\n"));
    process.exit(1);
  }
  console.log(`contract sizes OK: ${Object.keys(sizes).length} contracts checked, ${exempted.length} exempt`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
