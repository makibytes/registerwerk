#!/usr/bin/env bash
# Render every explicitly configured static source into a deployment-specific manifest.

set -euo pipefail

if (($# != 3)); then
  echo "Usage: $0 <graph-network> <env-suffix> <output-manifest>" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
node "$script_dir/render-subgraph-manifest.mjs" "$1" "$2" "$3"
