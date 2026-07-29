#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
rendered="$(mktemp)"
invalid_output="$(mktemp)"
cleanup() { rm -f "$rendered" "$invalid_output"; }
trap cleanup EXIT

export ASSET_TOKEN_FACTORY_ADDRESS_TEST=0x0000000000000000000000000000000000000001
export REPO_MARKET_FACTORY_ADDRESS_TEST=0x0000000000000000000000000000000000000002
export DVP_SETTLEMENT_ADDRESS_TEST=0x0000000000000000000000000000000000000003
export CONFIDENTIAL_FACTORY_ADDRESS_TEST=0x0000000000000000000000000000000000000004
export BOND_DESK_INSTANCES_TEST=0x0000000000000000000000000000000000000005@105,0x0000000000000000000000000000000000000006@106
export STABLECOIN_AMM_INSTANCES_TEST=0x0000000000000000000000000000000000000007@107,0x0000000000000000000000000000000000000008@108
export REPO_VAULT_INSTANCES_TEST=0x0000000000000000000000000000000000000009@109,0x000000000000000000000000000000000000000a@110
export ASSET_TOKEN_FACTORY_START_BLOCK_TEST=101
export REPO_MARKET_FACTORY_START_BLOCK_TEST=102
export DVP_SETTLEMENT_START_BLOCK_TEST=103
export CONFIDENTIAL_FACTORY_START_BLOCK_TEST=104

bash "$script_dir/render-subgraph-manifest.sh" sepolia TEST "$rendered"
for value in 1 2 3 4 5 6 7 8 9; do
  padded="$(printf '%040d' "$value")"
  grep -q "address: \"0x$padded\"" "$rendered"
done
grep -q 'address: "0x000000000000000000000000000000000000000a"' "$rendered"
for block in 101 102 103 104 105 106 107 108 109 110; do
  grep -q "startBlock: $block" "$rendered"
done
grep -q 'name: EwpgBondDesk_2' "$rendered"
grep -q 'name: StablecoinAmm_2' "$rendered"
grep -q 'name: EwpgRepoVault_2' "$rendered"

export REPO_VAULT_INSTANCES_TEST="$BOND_DESK_INSTANCES_TEST"
if bash "$script_dir/render-subgraph-manifest.sh" sepolia TEST "$invalid_output" 2>/dev/null; then
  echo "renderer accepted duplicate component addresses" >&2
  exit 1
fi

unset REPO_VAULT_INSTANCES_TEST
if bash "$script_dir/render-subgraph-manifest.sh" sepolia TEST "$invalid_output" 2>/dev/null; then
  echo "renderer silently accepted an absent RepoVault configuration" >&2
  exit 1
fi

export REPO_VAULT_INSTANCES_TEST=NONE
bash "$script_dir/render-subgraph-manifest.sh" sepolia TEST "$rendered"
grep -q 'NO CONFIGURED INSTANCES — OPERATOR ASSERTION; NOT CHAIN-DISCOVERED: EwpgRepoVault' "$rendered"
if grep -q 'name: EwpgRepoVault$' "$rendered"; then
  echo "renderer retained the RepoVault prototype despite an explicit NONE" >&2
  exit 1
fi

echo "Multi-instance subgraph rendering and fail-closed configuration validated"
