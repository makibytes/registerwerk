#!/usr/bin/env bash
# Deploys the eWpG subgraph to graph-node for all configured EVM chains.
# Run this after:
#   1. graph-node + IPFS are up (docker compose -f indexer/evm/docker-compose.yml up -d)
#   2. The AssetTokenFactory is deployed on the target chain
#   3. FACTORY_ADDRESS_<CHAIN> env vars are set (see .env.example)
#
# Usage:
#   ./deploy-subgraph.sh                  — deploy to all chains
#   ./deploy-subgraph.sh sepolia          — deploy to a single chain

set -euo pipefail
GRAPH_NODE="${GRAPH_NODE_ADMIN:-http://localhost:8020}"
SUBGRAPH_DIR="$(dirname "$0")/subgraph"

deploy_chain() {
  local NETWORK="$1"
  local SUBGRAPH_NAME="$2"
  local FACTORY_ADDR="$3"
  local START_BLOCK="${4:-0}"

  echo "▶ Deploying to network=$NETWORK subgraph=$SUBGRAPH_NAME factory=$FACTORY_ADDR"

  # Patch factory address into a temporary manifest
  TMP=$(mktemp)
  sed "s|address: \"0x0000000000000000000000000000000000000000\"|address: \"$FACTORY_ADDR\"|g;
       s|startBlock: 0|startBlock: $START_BLOCK|g;
       s|network: mainnet|network: $NETWORK|g" \
    "$SUBGRAPH_DIR/subgraph.yaml" > "$TMP"

  cd "$SUBGRAPH_DIR"
  npm run codegen
  graph build --manifest "$TMP"

  # Create subgraph on graph-node if it doesn't exist yet
  curl -sf -X POST "$GRAPH_NODE" \
    -H "Content-Type: application/json" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"subgraph_create\",\"params\":{\"name\":\"$SUBGRAPH_NAME\"}}" \
    || true  # ignore if already exists

  graph deploy \
    --node "$GRAPH_NODE" \
    --ipfs "${IPFS_API:-http://localhost:5001}" \
    --version-label "v0.1.0" \
    --manifest "$TMP" \
    "$SUBGRAPH_NAME"

  rm "$TMP"
  echo "✓ $SUBGRAPH_NAME deployed"
}

TARGET="${1:-all}"

case "$TARGET" in
  mainnet|all)
    deploy_chain "mainnet"      "ewpg/ethereum-mainnet"  "${FACTORY_ADDRESS_MAINNET:-0x0}"  "${START_BLOCK_MAINNET:-0}"
    ;;&
  sepolia|all)
    deploy_chain "sepolia"      "ewpg/ethereum-sepolia"  "${FACTORY_ADDRESS_SEPOLIA:-0x0}"  "${START_BLOCK_SEPOLIA:-0}"
    ;;&
  polygon|all)
    deploy_chain "polygon"      "ewpg/polygon-mainnet"   "${FACTORY_ADDRESS_POLYGON:-0x0}"  "${START_BLOCK_POLYGON:-0}"
    ;;&
  polygon-amoy|all)
    deploy_chain "polygon-amoy" "ewpg/polygon-amoy"      "${FACTORY_ADDRESS_POLYGON_AMOY:-0x0}" "0"
    ;;&
  base|all)
    deploy_chain "base"         "ewpg/base-mainnet"      "${FACTORY_ADDRESS_BASE:-0x0}"     "${START_BLOCK_BASE:-0}"
    ;;&
  base-sepolia|all)
    deploy_chain "base-sepolia" "ewpg/base-sepolia"      "${FACTORY_ADDRESS_BASE_SEPOLIA:-0x0}" "0"
    ;;
  *)
    echo "Unknown target: $TARGET"
    echo "Usage: $0 [mainnet|sepolia|polygon|polygon-amoy|base|base-sepolia|all]"
    exit 1
    ;;
esac

echo ""
echo "All done. Query at: http://localhost:8000/subgraphs/name/<subgraph-name>"
