---
title: Astrazione dell'account e transazioni sponsorizzate
description: ERC-4337 / EIP-7702 account intelligenti, gas sponsorizzato, passkey e permessi gasless.
---

# Astrazione dell'account e transazioni sponsorizzate { #account-abstraction-sponsored-transactions }

Registerwerk supporta transazioni sponsorizzate ERC-4337, account delegati EIP-7702, verifica dei
wallet ERC-1271 e un account passkey on-chain. Queste funzioni sono indipendenti
dall'[interoperabilità DeFi](./defi-interoperability.md).

## Fondazione: `WalletSignatureVerifier` { #foundation-walletsignatureverifier }

`WalletSignatureVerifier` (`orgidentity/api/WalletSignatureVerifier.java`, alla base di
`orgidentity/internal/MemberWalletService` e `marketplace/internal/ManifestSigningService`)
verifica le firme **sia** tramite recupero ECDSA (EOA semplici) **sia** tramite ERC-1271
`isValidSignature` (wallet smart-contract), in base al codice on-chain dell'indirizzo dichiarato.
Questo è il prerequisito per tutto ciò che segue — senza di esso, uno smart account non potrebbe
mai vincolarsi come wallet membro né firmare un manifesto del marketplace.

## EIP-7702: l'on-ramp verso lo smart account { #eip-7702-the-smart-account-on-ramp }

EIP-7702 (attivo dall'aggiornamento Pectra) permette a un EOA esistente di delegare il proprio
codice a un'implementazione smart-account **mantenendo esattamente lo stesso indirizzo**. Questo è
l'on-ramp naturale per Registerwerk in particolare, perché ogni parte del modello esistente si basa
su un indirizzo wallet fisso:

- `OrgRegistry._orgOf[wallet]` (`contracts/src/ecosystem/OrgRegistry.sol`) — un wallet, un'organizzazione, per indirizzo.
- T-REX `IdentityRegistry.registerIdentity(address, ...)` — identità/claim registrati per indirizzo.
- `EwpgCompliance.isWhitelisted(address)` — whitelist indicizzata per indirizzo.

Un cliente che aggiorna il proprio EOA esistente a uno smart account delegato tramite 7702 non
necessita di **alcuna migrazione** di quanto sopra — l'indirizzo non cambia, quindi l'appartenenza
all'org, la registrazione dell'identità e le voci della whitelist restano tutte valide. L'unico
nuovo requisito è il percorso ERC-1271 di `WalletSignatureVerifier` (già presente), dato che il
codice di un EOA delegato tramite 7702 implementa `isValidSignature` come qualsiasi altro wallet
smart-contract — incluso `EwpgPasskeyAccount` più sotto, che è esattamente un'implementazione
delegata di questo tipo.

`frontend-customer` centralizza l'accesso al wallet in `WalletService` e implementa l'esecuzione
EIP-7702/ERC-4337 opzionale in `SponsoredTxService`. La sponsorizzazione richiede
`environment.bundlerUrl`, un indirizzo paymaster e un ID policy risolto. La UI non crea né gestisce
istanze di `EwpgPasskeyAccount`.

## `EwpgPaymaster` — transazioni sponsorizzate { #ewpgpaymaster-sponsored-transactions }

`contracts/src/ecosystem/EwpgPaymaster.sol` è un `IPaymaster` ERC-4337 (verso EntryPoint v0.8, per
il supporto nativo a EIP-7702) che sponsorizza il gas per i clienti Registerwerk verificati:

- **Controllato in base a chi chiama, non a cosa chiama**: `validatePaymasterUserOp` verifica
  `PermissionOracle.isActiveMember(userOp.sender)` e `hasClaimTopic(userOp.sender, KYC)` — non
  sponsorizza mai il gas per un wallet non verificato. Poiché un EOA delegato tramite 7702 mantiene
  il proprio indirizzo originale, `userOp.sender` *è* l'indirizzo wallet membro già esistente del
  cliente, quindi questo controllo legge direttamente lo stesso oracolo usato da ogni altro
  contratto dell'ecosistema. Analizzare il `callData` di uno smart account arbitrario per limitare
  *quale contratto* viene chiamato dipende dall'implementazione dell'account e resta
  deliberatamente fuori ambito — vedere il NatSpec del contratto.
- **La sponsorizzazione è delimitata da un `policyId` opaco** (codificato in `paymasterAndData`),
  finanziato tramite `fundSponsorship(policyId)` da chiunque sia disposto a sponsorizzare
  (l'operatore o la tesoreria di un emittente) — sia stanziando un budget interno sia depositando
  nell'EntryPoint.
- **Un limite di spesa per wallet** (`setWalletBudgetCap`) limita quanto di un budget di policy
  *condiviso* un singolo wallet può consumare, in aggiunta al budget aggregato della policy stessa.
- Sostenuto dall'entità `deployment/api/GasSponsorshipPolicy` (backend) — rispecchia il pattern già
  esistente di `MintControlRule`: un override per singola distribuzione, oppure un valore
  predefinito a livello di emittente che le distribuzioni future di quell'emittente ereditano
  finché non ricevono un proprio override (`GasSponsorshipService.resolveEffectivePolicy`,
  `asset/web/GasSponsorshipController`). Il `policyId` on-chain per una determinata riga è
  `keccak256(id.toString())`. Questo livello backend è solo di configurazione — non guida ancora un
  job di sincronizzazione on-chain che spinga automaticamente i budget nel paymaster; oggi un
  operatore/emittente finanzia `EwpgPaymaster.fundSponsorship` direttamente.
- UI operatore: la pagina dei dettagli asset di `frontend-operator` ha una scheda **Gas
  Sponsorship** per distribuzione (per impostare/rimuovere un override specifico della
  distribuzione), e la pagina dei dettagli cliente ne ha una per gli emittenti (per impostare il
  valore predefinito a livello di emittente che le nuove distribuzioni ereditano) — entrambe
  sostenute da `core/api/gas-sponsorship.service.ts`, che mostra la policy attualmente efficace e
  se si tratta di un override o di un valore predefinito ereditato.
- Script di deploy: `contracts/script/DeployLiquidityDapps.s.sol` esegue il deploy di
  `EwpgPaymaster` (EntryPoint predefinito `ERC4337Utils.ENTRYPOINT_V08`) insieme a
  `EwpgRepoFacility` — tenuto separato da `DeployExampleDapps.s.sol` poiché entrambi usano pragma
  `^0.8.36` e non possono condividere un'unità di compilazione con gli import dipendenti da
  erc3643 di quello script (fissati esattamente a `0.8.30`).
- Dati demo: `EcosystemDemoDataSeeder` inserisce tre righe `GasSponsorshipPolicy` — il valore
  predefinito a livello di emittente di Meridian Capital (sponsor `ISSUER`), il valore predefinito
  di Aurora Finance finanziato invece dall'operatore (sponsor `OPERATOR`, per mostrare l'altro tipo
  di sponsor), e un override a livello di distribuzione sulla distribuzione Green Bond di punta di
  Meridian (`OPERATOR`, a dimostrazione della precedenza dell'override sul valore predefinito).
- Test: `contracts/test/ecosystem/EwpgPaymaster.t.sol` (rispetto a un `MockEntryPoint` minimale —
  vedere il suo NatSpec per capire perché non serve una simulazione completa di `handleOps` per
  testare la logica contabile del paymaster stesso), `backend/.../unit/GasSponsorshipServiceTest.java`.

## `EwpgPasskeyAccount` — firmatari passkey per il retail { #ewpgpasskeyaccount-passkey-signers-for-retail }

`contracts/src/ecosystem/EwpgPasskeyAccount.sol` è uno smart account ERC-4337 minimale protetto da
una passkey WebAuthn/secp256r1 invece di una chiave ECDSA gestita tramite seed phrase, che compone
tre elementi già inclusi tramite `contracts/lib/openzeppelin-contracts` (nessuna nuova dipendenza):
`Account` di OZ (`validateUserOp` ERC-4337), `SignerWebAuthn` (verifica della firma passkey) e
`ERC7821` (esecuzione batch minimale). Implementa anche ERC-1271, così si vincola come wallet
membro di Registerwerk esattamente come qualsiasi altro wallet smart-contract.

Abbinato a `EwpgPaymaster`, il flusso di un investitore retail dall'onboarding alla prima
sottoscrizione non richiede alcuna seed phrase né alcun token per il gas — autenticazione
biometrica tramite passkey più esecuzione sponsorizzata. Nota: `contracts/foundry.toml` ora abilita
l'ottimizzatore Solidity (`optimizer = true`, `optimizer_runs = 200`, in linea con il valore
predefinito della libreria OZ inclusa) — senza di esso, l'analisi della firma WebAuthn genera
l'errore "stack too deep".

I test (`contracts/test/ecosystem/EwpgPasskeyAccount.t.sol`) costruiscono vere asserzioni di
autenticazione WebAuthn usando i cheatcode P256 nativi di Foundry
(`vm.publicKeyP256`/`vm.signP256`), incluso un esempio pratico dell'unica insidia non ovvia:
`abi.encode(structValue)` aggiunge una parola di offset extra di primo livello per una struct
contenente campi dinamici, cosa che `WebAuthn.tryDecodeAuth` non si aspetta — occorre invece
codificare i campi della struct come argomenti separati (vedere l'helper `_sign` del test e il
relativo commento inline).

## Permessi gasless { #gasless-permits }

`EwpgBondDesk.subscribeWithPermit` utilizza un `permit` EIP-2612 firmato invece di richiedere una
transazione `approve` separata e precedente — dimezza il numero di transazioni e si combina
naturalmente con la sponsorizzazione di `EwpgPaymaster` (permit + esecuzione sponsorizzata = UX a
zero token per il gas). `MockStablecoin` ora implementa `ERC20Permit`, così l'esempio/i test
possono verificare questo flusso end-to-end
(`test_subscribeWithPermit_succeedsWithoutPriorApproval` in
`contracts/test/examples/EwpgBondDesk.t.sol`). Non tutti i canali di pagamento reali lo supportano:
USDC implementa EIP-2612 nativamente; verificare il supporto di AllUnity Euro prima di collegarci
`subscribeWithPermit` in produzione — il percorso semplice `subscribe` resta comunque disponibile
in entrambi i casi.

## Formati di firma { #signature-formats }

Il binding del wallet e la firma dei manifesti usano `personal_sign`. `WalletSignatureVerifier`
accetta questo formato per EOA e wallet ERC-1271, ma non firme EIP-712 con dati tipizzati.
