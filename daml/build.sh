#!/usr/bin/env bash
# Builds the Daml module and generates Java bindings for the Canton Token Standard.
# Run from the daml/ directory or the monorepo root.
#
# Prerequisites:
#   - Daml SDK installed: https://docs.daml.com/getting-started/installation.html
#   - daml command on PATH

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_SRC="$SCRIPT_DIR/../backend/src/main"
GENERATED_SOURCES="$BACKEND_SRC/java/de/makibytes/registerwerk/canton/generated"

echo "==> Building Daml module..."
cd "$SCRIPT_DIR"
daml build

echo "==> Generating Java bindings..."
mkdir -p "$GENERATED_SOURCES"
daml codegen java \
  --output-directory "$GENERATED_SOURCES" \
  --package-prefix "de.makibytes.registerwerk.canton.generated" \
  .daml/dist/registerwerk-canton-0.1.0.dar

echo "==> Done. Java sources written to $GENERATED_SOURCES"
echo "    Run 'cd backend && ./mvnw compile' to compile them."
