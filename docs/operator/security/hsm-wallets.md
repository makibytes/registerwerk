---
title: HSM-backed wallets
---

# HSM-backed wallets

Registerwerk signs EVM transactions through an `EvmSigner` boundary. Software keystores and
PKCS#11 devices implement the same interface; transaction services never receive a private key.
An HSM wallet stores only its checksummed address and PKCS#11 object label in PostgreSQL. Raw and
keystore export are disabled for these wallets.

## Demo: SoftHSM

`docker compose up` starts SoftHSM, imports Anvil's documented first development key as the
non-exportable `registerwerk-operator` object, and enrolls it as the default EVM operator wallet.
The HSM challenge signature is verified against
`0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266` before enrollment. The key and default PINs are
public fixtures and must never leave this disposable environment.

SoftHSM and the unprivileged backend share the token directory through fixed group ID `2000`.
The token files are group read/write and inaccessible to other container users.

## Production: Thales, Utimaco, or another PKCS#11 device

Install the vendor client and mount a SunPKCS11 configuration into the backend container. Then
set:

```dotenv
REGISTERWERK_HSM_ENABLED=true
REGISTERWERK_HSM_PROFILE=THALES
REGISTERWERK_HSM_PROVIDER_CONFIG=/etc/registerwerk/pkcs11.cfg
REGISTERWERK_HSM_PIN=<secret reference injected at runtime>
REGISTERWERK_HSM_SIGNATURE_ALGORITHM=
```

The profile selects the vendor adapter; the SunPKCS11 file selects its native library, slot and
token. A Registerwerk instance supports one HSM configuration. High availability, clustering and
key replication remain responsibilities of the HSM appliance/client.

After the token key exists, use **Operator Portal → Wallets → Attach HSM key**. Enter its object
label and public EVM address. Registerwerk signs an enrollment digest and refuses the wallet if
recovery does not yield that address.

For production readiness:

- use a secp256k1 key with raw-digest ECDSA support;
- make the key non-extractable and restrict it to signing;
- inject the PIN from the platform secret manager, never an image or committed `.env`;
- transfer wallet/default management to dual-control operator accounts;
- test backup, restore and key ceremonies on the vendor's staging appliance;
- monitor signing failures and audit every wallet/default change.

Changing vendor only changes the profile, mounted provider configuration and client library. No
blockchain service or contract deployment code changes.
