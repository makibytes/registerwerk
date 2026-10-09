#!/usr/bin/env node
// Enforces the contract-size limits of the chains Registerwerk targets on every contract
// `forge build --sizes` reports, so nothing deployable silently outgrows them.
//
// Registerwerk targets Glamsterdam-era chains only. EIP-7954 raises the limits to 65,536 B of runtime
// code (was 24,576, EIP-170) and 131,072 B of initcode (was 49,152, EIP-3860). Forge's own
// `runtime_margin` / `init_margin` still measure against the old limits, so this script computes the
// margins itself. Size is no longer a hard wall below 64 KiB, but it is the main cost lever: since
// EIP-8037 every deployed byte costs 1,530 gas of state gas (it was 200), so the script also prints
// what the largest contracts cost to deploy (information only).
//
// It keeps a shrink-only exception list for known, owned debt:
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

/** EIP-7954 (Glamsterdam): maximum runtime code size, in bytes. */
export const MAX_RUNTIME = 65_536;
/** EIP-7954 (Glamsterdam): maximum initcode size, in bytes. */
export const MAX_INITCODE = 131_072;
/** EIP-8037: state gas per deployed byte (`CPSB`), and the account-creation share in bytes. */
export const STATE_GAS_PER_BYTE = 1530;
export const STATE_BYTES_PER_NEW_ACCOUNT = 120;

/**
 * Known oversize contracts. `runtime`/`init` are the sizes in bytes measured when the exemption
 * was recorded; they are ceilings, not targets.
 */
export const EXEMPTIONS = {};

/** Gas to deploy `runtimeBytes` of code under EIP-8037 (account creation + code deposit, state gas only). */
export function deployStateGas(runtimeBytes) {
  return (STATE_BYTES_PER_NEW_ACCOUNT + runtimeBytes) * STATE_GAS_PER_BYTE;
}

/**
 * @param {Record<string, {runtime_size:number, init_size:number}>} sizes
 *        the JSON printed by `forge build --sizes --json` (only the two sizes are used)
 * @param {typeof EXEMPTIONS} exemptions
 * @returns {string[]} problems; empty when the build may pass
 */
export function evaluate(sizes, exemptions = EXEMPTIONS) {
  const problems = [];
  for (const [name, size] of Object.entries(sizes)) {
    const runtimeMargin = MAX_RUNTIME - size.runtime_size;
    const initMargin = MAX_INITCODE - size.init_size;
    const over = runtimeMargin < 0 || initMargin < 0;
    const exemption = exemptions[name];
    if (!exemption) {
      if (over) {
        problems.push(
          `${name}: runtime ${size.runtime_size} B (margin ${runtimeMargin}), initcode ${size.init_size} B `
          + `(margin ${initMargin}) is over the EIP-7954 limit (${MAX_RUNTIME} B / ${MAX_INITCODE} B) and has no exemption`,
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
  const largest = Object.entries(sizes)
    .sort(([, a], [, b]) => b.runtime_size - a.runtime_size)
    .slice(0, 5)
    .map(([name, size]) => `${name} ${size.runtime_size} B ≈ ${(deployStateGas(size.runtime_size) / 1e6).toFixed(1)}M gas`);
  console.log(`largest contracts (deploy state gas under EIP-8037): ${largest.join("; ")}`);
  console.log(`contract sizes OK: ${Object.keys(sizes).length} contracts checked, ${exempted.length} exempt`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
