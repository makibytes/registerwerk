# Daml Module — Registerwerk Canton

This directory contains the Daml model for Registerwerk's Canton integration.

## Overview

Registerwerk uses the **Daml Token Standard (CIP-0056)** for Canton tokens.
The standard is pre-deployed on the public Canton Network (mainnet and devnet),
so no custom DAR upload is required for production deployments on CN.

For **private Canton domains**, the token-standard DAR must be loaded on the
participant during bootstrap (see `indexer/canton/config/bootstrap.canton`).

## Build

```bash
# Install Daml SDK first:
curl -sSL https://get.daml.com/ | sh -s 3.2.0

# Build + generate Java bindings:
cd daml && ./build.sh
```

This generates strongly-typed Java builders under
`backend/src/main/java/de/makibytes/registerwerk/canton/generated/`.
The generated code is consumed by `CantonTokenService`.

## Directory layout

```
daml/
  daml.yaml       — SDK version + Token Standard dependency
  build.sh        — build + codegen script
  daml/           — Daml source files (eWpG extension model, v2+)
```

## Custom eWpG extension (v2 follow-up)

The v1 integration uses the Token Standard as-is. A future DAR will extend
it with eWpG-specific compliance rules:

- KYC whitelist enforced at instrument level (only whitelisted parties may hold)
- Regulator party auto-signatory on ForceTransfer
- On-ledger KYC claim references (linked to ONCHAINID IDs)
- Transfer hooks for AML screening callbacks

When ready, add `.daml` source files here and update `daml.yaml` accordingly.
