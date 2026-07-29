#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

export const evidenceCommands = Object.freeze({
  "contract-erc3525": {
    cwd: "contracts",
    command: "forge",
    args: ["test", "--match-path", "test/EwpgERC3525Test.t.sol"]
  },
  "backend-regreporting": {
    cwd: "backend",
    command: "./mvnw",
    args: [
      "-Dtest=MifirReportingServiceTest,Dac8ExportServiceTest,RegReportingProductionReadinessCheckTest,RegReportSubmissionsTest,RegReportStalenessMonitorTest,RegReportingTransportStatusMigrationIT",
      "test"
    ]
  },
  "indexer-subgraph": {
    cwd: "indexer/evm/subgraph",
    command: "npm",
    args: ["test"]
  }
});

export function runEvidence(commandId) {
  const definition = evidenceCommands[commandId];
  if (!definition) {
    throw new Error(`Unknown claim evidence command: ${commandId}`);
  }
  const result = spawnSync(definition.command, definition.args, {
    cwd: path.join(repo, definition.cwd),
    stdio: "inherit",
    shell: false
  });
  if (result.error) throw result.error;
  return result.status ?? 1;
}

const invokedDirectly = process.argv[1]
  && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) {
  try {
    process.exitCode = runEvidence(process.argv[2]);
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 2;
  }
}
