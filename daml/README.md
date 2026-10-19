# Daml Module — Registerwerk Canton

This directory contains the Daml model for Registerwerk's Canton integration.

## Overview

Registerwerk uses the **Daml Token Standard (CIP-0056)** for Canton tokens.
The standard is pre-deployed on the public Canton Network (mainnet and devnet),
so no custom DAR upload is required for production deployments on CN.

For **private Canton domains**, the token-standard DAR must be loaded on the
participant during bootstrap (see `indexer/canton/config/bootstrap.canton`).

## Build and test

The model is built with [dpm](https://github.com/digital-asset/dpm) (the Daml Package Manager),
which fetches the SDK pinned by `sdk-version` in `daml.yaml`. The CI job `Daml (Canton) CI` runs the
same commands.

```bash
cd daml
dpm install        # the SDK from daml.yaml
dpm build          # -> .daml/dist/registerwerk-canton-0.1.0.dar (Daml-LF 2.1)

cd test            # Daml Script tests; they depend on the DAR built above
dpm install
dpm test
```

The pinned SDK is 3.6.0, the newest release (`dpm version --all` calls it `latest`, `testnet` and `devnet`).
The Canton Network `mainnet` channel was still on 3.5.12 when this was written; the DAR targets Daml-LF 2.1,
which both lines accept, but check `dpm version --all` and your participant's version before you deploy.

`damlc` is shipped for x86-64 Linux and macOS only. On an Apple-silicon machine without Rosetta,
run the commands in a `linux/amd64` container with a JRE.

The backend does not use generated Java bindings: `CantonBondService` encodes the template
arguments by hand (package-name addressing, `#registerwerk-canton`). A change to a template
argument therefore needs a matching change in that encoder; its profile tests (`mvn verify -Pcanton`)
catch a mismatch.

## Directory layout

```
daml/
  daml.yaml       — SDK version, Daml-LF target
  daml/           — the bond templates (fixed, floating, zero-coupon)
  test/           — Daml Script tests (a separate package, so the production DAR has no daml-script dependency)
```

## Custom eWpG extension (v2 follow-up)

The v1 integration uses the Token Standard as-is. A future DAR will extend
it with eWpG-specific compliance rules:

- KYC whitelist enforced at instrument level (only whitelisted parties may hold)
- Regulator party auto-signatory on ForceTransfer
- On-ledger KYC claim references (linked to ONCHAINID IDs)
- Transfer hooks for AML screening callbacks

When ready, add `.daml` source files here and update `daml.yaml` accordingly.
