#!/usr/bin/env bash
# Deploys the Registerwerk EVM subgraph with independently configured component provenance.
# Each static data source needs COMPONENT_ADDRESS_<CHAIN_SUFFIX>; component-specific start blocks
# are optional and default to 0 via COMPONENT_START_BLOCK_<CHAIN_SUFFIX>.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUBGRAPH_DIR="$SCRIPT_DIR/subgraph"
RENDER_SCRIPT="$SCRIPT_DIR/render-subgraph-manifest.sh"
GRAPH_NODE="${GRAPH_NODE_ADMIN:-http://localhost:8020}"
GRAPH_CLI="${GRAPH_CLI:-$SUBGRAPH_DIR/node_modules/.bin/graph}"
TMP_FILES=()

if [[ ! -x "$GRAPH_CLI" ]]; then
  echo "Graph CLI is unavailable at $GRAPH_CLI; run npm install in $SUBGRAPH_DIR or set GRAPH_CLI" >&2
  exit 1
fi
if [[ "${SUBGRAPH_VALIDATE_ONLY:-false}" != "true" && -z "${SUBGRAPH_VERSION_LABEL:-}" ]]; then
  echo "SUBGRAPH_VERSION_LABEL is required for deployment and must be new for each target graph" >&2
  exit 1
fi

cleanup() {
  if ((${#TMP_FILES[@]} > 0)); then
    rm -f "${TMP_FILES[@]}"
  fi
}
trap cleanup EXIT

deploy_chain() {
  local network="$1"
  local subgraph_name="$2"
  local env_suffix="$3"
  local manifest
  # Keep the rendered file beside subgraph.yaml so its relative schema, ABI and
  # mapping paths resolve identically during Graph CLI validation and deployment.
  manifest="$(mktemp "$SUBGRAPH_DIR/subgraph.rendered.XXXXXX")"
  TMP_FILES+=("$manifest")

  bash "$RENDER_SCRIPT" "$network" "$env_suffix" "$manifest"
  echo "Deploying network=$network subgraph=$subgraph_name with independently configured component addresses"

  (
    cd "$SUBGRAPH_DIR"
    npm run validate:abi
    npm run codegen
    "$GRAPH_CLI" build "$manifest"
  )

  if [[ "${SUBGRAPH_VALIDATE_ONLY:-false}" == "true" ]]; then
    echo "Validated rendered manifest for $network (deployment skipped)"
    return
  fi

  curl -sf -X POST "$GRAPH_NODE" \
    -H "Content-Type: application/json" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"subgraph_create\",\"params\":{\"name\":\"$subgraph_name\"}}" \
    || true

  (
    cd "$SUBGRAPH_DIR"
    "$GRAPH_CLI" deploy "$subgraph_name" "$manifest" \
      --node "$GRAPH_NODE" \
      --ipfs "${IPFS_API:-http://localhost:5001}" \
      --version-label "$SUBGRAPH_VERSION_LABEL"
  )
}

run_target() {
  case "$1" in
    mainnet) deploy_chain "mainnet" "ewpg/ethereum-mainnet" "MAINNET" ;;
    sepolia) deploy_chain "sepolia" "ewpg/ethereum-sepolia" "SEPOLIA" ;;
    polygon) deploy_chain "polygon" "ewpg/polygon-mainnet" "POLYGON" ;;
    polygon-amoy) deploy_chain "polygon-amoy" "ewpg/polygon-amoy" "POLYGON_AMOY" ;;
    base) deploy_chain "base" "ewpg/base-mainnet" "BASE" ;;
    base-sepolia) deploy_chain "base-sepolia" "ewpg/base-sepolia" "BASE_SEPOLIA" ;;
    arbitrum-one) deploy_chain "arbitrum-one" "ewpg/arbitrum-mainnet" "ARBITRUM" ;;
    arbitrum-sepolia) deploy_chain "arbitrum-sepolia" "ewpg/arbitrum-sepolia" "ARBITRUM_SEPOLIA" ;;
    avalanche) deploy_chain "avalanche" "ewpg/avalanche-mainnet" "AVALANCHE" ;;
    avalanche-fuji) deploy_chain "avalanche-fuji" "ewpg/avalanche-fuji" "AVALANCHE_FUJI" ;;
    optimism) deploy_chain "optimism" "ewpg/optimism-mainnet" "OPTIMISM" ;;
    optimism-sepolia) deploy_chain "optimism-sepolia" "ewpg/optimism-sepolia" "OPTIMISM_SEPOLIA" ;;
    *)
      echo "Unknown target: $1" >&2
      echo "Usage: $0 [mainnet|sepolia|polygon|polygon-amoy|base|base-sepolia|arbitrum-one|arbitrum-sepolia|avalanche|avalanche-fuji|optimism|optimism-sepolia|all]" >&2
      exit 1
      ;;
  esac
}

target="${1:-all}"
if [[ "$target" == "all" ]]; then
  for network in mainnet sepolia polygon polygon-amoy base base-sepolia arbitrum-one arbitrum-sepolia avalanche avalanche-fuji optimism optimism-sepolia; do
    run_target "$network"
  done
else
  run_target "$target"
fi

echo "Subgraph operation complete. Query at http://localhost:8000/subgraphs/name/<subgraph-name>"
