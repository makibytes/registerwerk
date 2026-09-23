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

Un cliente che aggiorna il proprio EOA esistente a uno smart account delegato tramite 7702 non ha
bisogno di **alcuna migrazione** di quanto sopra: l'indirizzo non cambia, quindi l'appartenenza
all'org, la registrazione dell'identità e le voci della whitelist restano tutte valide. L'unico
requisito nuovo è il percorso ERC-1271 di `WalletSignatureVerifier` (già presente), poiché il
codice di un EOA delegato tramite 7702 implementa `isValidSignature` come qualsiasi altro wallet a
smart contract. Il delegato usato dal portale clienti è `Simple7702Account` di viem.
`EwpgPasskeyAccount` (sotto) **non** è un delegato 7702: passkey e guardian sono stato della singola
istanza deployata, quindi un EOA che delegasse a essa non avrebbe alcuna passkey (ogni firma viene
rifiutata) e condividerebbe un unico guardian con tutti gli altri EOA deleganti.

`frontend-customer` centralizza l'accesso al wallet in `WalletService` e implementa l'esecuzione
EIP-7702/ERC-4337 opzionale in `SponsoredTxService`. La sponsorizzazione richiede
`environment.bundlerUrl` e, per ogni UserOperation, un **voucher** dal backend (sezione
successiva). Se il backend rifiuta il voucher, il servizio solleva `SponsorshipUnavailableError`
con il motivo; `sendWithSponsorshipFallback` invia allora la stessa chiamata come normale
transazione pagata dall'utente e lo segnala. Non ripiega mai in silenzio. L'interfaccia non crea né
gestisce istanze di `EwpgPasskeyAccount`.

## `EwpgPaymaster` — transazioni sponsorizzate { #ewpgpaymaster-sponsored-transactions }

`contracts/src/ecosystem/EwpgPaymaster.sol` è un **verifying paymaster** ERC-4337 (contro
EntryPoint v0.8, per il supporto nativo a EIP-7702) che sponsorizza il gas per i clienti
Registerwerk verificati.

**Voucher.** Una UserOperation viene sponsorizzata solo se porta un voucher firmato dal firmatario
di voucher registrato per la policy:

```
paymasterData = policyId (32) ‖ validUntil (6) ‖ validAfter (6) ‖ maxFeePerGasCap (16) ‖ signature (65)
```

La firma è una firma EIP-191 (`personal_sign`) su `EwpgPaymaster.getHash(...)`. Questo digest
copre ogni campo della UserOperation (sender, nonce, `keccak(initCode)`, `keccak(callData)`, i
limiti di gas dell'account, i limiti di gas del paymaster per verifica e postOp,
`preVerificationGas` e `gasFees`), oltre a `block.chainid`, l'indirizzo del paymaster, l'id della
policy, la finestra di validità e il tetto del prezzo del gas, sotto il tag di dominio
`keccak256("EwpgPaymasterVoucher(v1)")`. Esclude deliberatamente i byte della firma: si trovano in
`paymasterAndData`, che fa parte di `userOpHash`, quindi un voucher non può firmare `userOpHash`.
Un firmatario errato restituisce `SIG_VALIDATION_FAILED` (l'EntryPoint segnala `AA34`) e un voucher
scaduto restituisce `AA32`. La validazione va inoltre in revert se la policy è inattiva o non
registrata, se `maxFeePerGas` supera il tetto firmato, se `paymasterPostOpGasLimit` è inferiore a
50.000 gas o se il sender non è un membro attivo con KYC (difesa in profondità; anche il backend lo
verifica).

**Emittente dei voucher (backend).** `POST /api/v1/gas-sponsorship/vouchers` (JWT cliente,
`asset/web/GasSponsorshipVoucherController`, `asset/internal/GasSponsorshipVoucherService`) riceve
l'id del deployment e la UserOperation preparata. Prima di firmare verifica che:

- la policy effettiva del deployment sia **attiva** nel database. Disattivare una policy blocca
  subito i voucher, anche prima che il flag on-chain venga modificato.
- il sender sia un **wallet membro attivo dell'entità giuridica del chiamante** su quella chain.
- il sender **detenga l'asset**: abbia un'iscrizione attiva nel registro (non una riga di nominee
  pool) dell'entità del chiamante per l'asset del deployment. I primi sottoscrittori senza
  iscrizione non vengono sponsorizzati e pagano il proprio gas.
- **ambito** (predefinito finché il prodotto non decide diversamente): ogni chiamata del batch
  `execute`/`executeBatch` sia diretta al contratto del token del deployment senza valore, e
  `initCode` sia vuoto o soltanto il marcatore EIP-7702. I deployment tramite factory vengono
  rifiutati.
- **gas**: `maxFeePerGas` ≤ `registerwerk.paymaster.max-fee-per-gas-cap-wei` (il tetto viene
  firmato nel voucher), la somma dei limiti di gas ≤ `max-total-gas` e il gas di postOp ≥ 50.000.
- il **tetto mensile** della policy (`monthlyCapEth`) sia rispettato. Ogni voucher emesso conta
  per il suo costo nel caso peggiore (`Σ limiti di gas × maxFeePerGas`, il prefund dell'EntryPoint)
  e viene registrato in `gas_sponsorship_voucher`, così il tetto è efficace prima che qualsiasi
  operazione venga regolata. Un voucher conta una sola volta per `(policy, sender, nonce della
  UserOperation)`: una nuova richiesta per lo stesso nonce sostituisce il voucher precedente.
  Un'entità giuridica può usare al massimo `registerwerk.paymaster.entity-monthly-cap-share`
  (predefinito 10 %) del tetto al mese, così una sola organizzazione non esaurisce il budget di un
  emittente a scapito degli altri titolari.

Ogni voucher è valido per `voucher-validity-seconds` (predefinito 300) ed emette l'evento di audit
`GAS_SPONSORSHIP_VOUCHER_ISSUED`. La chiave di firma di sviluppo è
`registerwerk.paymaster.voucher-signer-key` (`REGISTERWERK_PAYMASTER_VOUCHER_SIGNER_KEY`). È
incapsulata nell'astrazione `EvmSigner` del modulo wallet; in produzione passa a KMS/HSM. **Non**
deve mai essere il wallet di firma dei claim (trusted issuer): una chiave di voucher può spendere
budget di sponsorizzazione. Se vuota, la sponsorizzazione è disattivata. Gli indirizzi del
paymaster si configurano per chain in `registerwerk.paymaster.addresses`
(`PAYMASTER_<CHAIN>_<NETWORK>`). Il `policyId` on-chain di una riga `GasSponsorshipPolicy` è
`keccak256(id.toString())`.

**Contabilità del budget.**

- `registerPolicy(policyId, signer, orgCap)` registra il chiamante come **finanziatore** (funder)
  della policy, il firmatario dei voucher e un tetto per org diverso da zero. Può finanziare la
  policy nella stessa chiamata.
- `fundSponsorship(policyId)` ricarica. Solo il finanziatore può chiamarla, e solo lui può ruotare
  il firmatario (`setPolicySigner`), perché un firmatario può spendere la policy.
- La validazione **riserva** il `maxCost` dell'operazione dal saldo della policy, così più
  operazioni nello stesso bundle non possono essere validate tutte sullo stesso saldo. Verifica
  inoltre il tetto dell'org del sender rispetto a speso + riservato + `maxCost`. Il tetto è legato a
  `orgOf(sender)`, quindi nuovi wallet della stessa org non lo moltiplicano.
- `postOp` non va mai in revert. Contabilizza `min(maxCost, actualGasCost + (postOpGasLimit +
  10.000) × feePerGas)` e restituisce il resto della riserva. EntryPoint v0.7/v0.8 passano a
  `postOp` il costo *prima* di aggiungere il gas proprio di postOp e la penalità per il gas non
  usato; contabilizzare solo `actualGasCost` farebbe salire nel tempo i registri sopra il deposito
  reale. L'importo contabilizzato è un limite superiore; la piccola differenza resta nel deposito
  come eccedenza.
- `depositSurplus()` = deposito nell'EntryPoint − (Σ saldi + Σ riserve). Non deve mai diventare
  negativo. Monitorarlo come `paymaster_deposit_minus_booked_wei` con allarme sotto 0.

**Proprietà e controlli (on-chain).**

- `setPolicyActive(policyId, bool)` è l'interruttore di emergenza on-chain. Possono chiamarlo il
  finanziatore o un titolare di `paymaster.configure`.
- `withdrawPolicy(policyId, amount)` restituisce budget non riservato dal deposito nell'EntryPoint.
  Il finanziatore o un titolare di `paymaster.configure` possono avviarla, ma **paga sempre il
  finanziatore registrato**. Nessuno può reindirizzarla.
- `addStake(unstakeDelaySec)` (richiede `paymaster.configure`), `unlockStake()` e
  `withdrawStake()` gestiscono lo stake nell'EntryPoint. Il primo staker viene registrato come
  `stakeFunder` e lo stake viene sempre restituito a quell'indirizzo. Solo `stakeFunder` può chiamare
  `unlockStake()`: senza stake i bundler pubblici scartano il paymaster, quindi un altro titolare di
  `paymaster.configure` non può avviare l'unstake.

**Interfaccia operatore.** La pagina di dettaglio dell'asset di `frontend-operator` ha una scheda
**Gas Sponsorship** per deployment (impostare/rimuovere un override specifico del deployment). La
pagina di dettaglio cliente ne ha una per gli emittenti (impostare il default dell'emittente
ereditato dai nuovi deployment). Entrambe usano `core/api/gas-sponsorship.service.ts`. La scheda
dell'asset mostra anche lo stato on-chain della policy: flag di attività, saldo disponibile e
riservato, tetto per org, finanziatore e firmatario dei voucher
(`GET /assets/{id}/deployments/{depId}/gas-sponsorship/onchain`). Avvisa quando una policy è
disattivata nel database ma ancora attiva on-chain. `GET /gas-sponsorship/voucher-signer`
restituisce l'indirizzo che ogni policy deve registrare come firmatario.

- Script di deploy: `contracts/script/DeployLiquidityDapps.s.sol` esegue il deploy di
  `EwpgPaymaster` con EntryPoint `ERC4337Utils.ENTRYPOINT_V08` insieme a `EwpgRepoFacility`.
- Dati demo: `EcosystemDemoDataSeeder` crea tre righe `GasSponsorshipPolicy`: il default proprio di
  Meridian Capital (sponsor `ISSUER`), il default di Aurora Finance finanziato invece dall'operatore
  (sponsor `OPERATOR`, per mostrare l'altro tipo) e un override a livello di deployment sul Green
  Bond di punta di Meridian (`OPERATOR`, che mostra la precedenza dell'override sul default).
- Test: `contracts/test/ecosystem/EwpgPaymaster.t.sol` fa passare ogni percorso sponsorizzato per
  `handleOps` del **vero EntryPoint v0.8.0** (incluso solo per i test in `contracts/test/aa-v08/`),
  con test di regressione per gli scenari di svuotamento citati nel rollout più sotto.
  `backend/.../asset/internal/GasSponsorshipVoucherServiceTest.java` e
  `unit/GasSponsorshipVoucherDigestTest.java` vincolano il digest Java a quello Solidity con un
  vettore di test condiviso.

### Stake, rollout e dismissione del paymaster precedente { #paymaster-operations }

La validazione scrive storage (riserve) e legge altri contratti (`PermissionOracle`), quindi
secondo ERC-7562 i bundler pubblici accettano il paymaster solo se ha uno **stake**. Per chain:

| Chain | Stake consigliato | Ritardo di unstake |
|---|---|---|
| Ethereum mainnet | ≥ 1 ETH | ≥ 1 giorno (86.400 s) |
| L2 (Base, Arbitrum, Optimism, Polygon) | minimo del bundler (di solito 0,1–1 del token nativo) | ≥ 1 giorno |
| Testnet | minimo del bundler | ≥ 1 giorno |

Verificare il minimo pubblicato dal fornitore del bundler prima dello stake. Uno stake con un
ritardo inferiore a quello richiesto dal bundler è considerato assente.

Rollout:

1. Il paymaster deployato prima di questa modifica (HEAD `b810acb` e precedenti) è immutabile e
   insicuro: qualsiasi membro poteva spendere qualsiasi policy, il prezzo del gas era illimitato e
   un bundle poteva spendere più del disponibile. **Smettere subito di finanziarlo.** Non ha una
   funzione di prelievo, quindi nessuna opzione di finanziamento punta più a esso.
2. Eseguire il deploy del nuovo `EwpgPaymaster`. Chiamare `addStake` dal wallet dell'operatore e
   impostare `registerwerk.paymaster.addresses.<chain>` e la chiave del firmatario dei voucher.
3. Ogni finanziatore chiama `registerPolicy(keccak256(policyRowId), voucherSigner, orgCap)` con il
   budget.
4. Registrare per chain l'ETH rimasto nel vecchio paymaster (`EntryPoint.balanceOf(old)`) come
   **saldo bloccato**. Può essere consumato solo da operazioni sponsorizzate, cosa da evitare dati i
   difetti sopra descritti.

Limitazione nota: `registerPolicy` assegna un id di policy al primo che lo registra. Chi anticipa la
registrazione (front-running) può bloccare quell'id, ma non può prelevare fondi. Il finanziatore
registra allora la policy con un nuovo id di riga.

## `EwpgPasskeyAccount` — firmatari passkey per il retail { #ewpgpasskeyaccount-passkey-signers-for-retail }

`contracts/src/ecosystem/EwpgPasskeyAccount.sol` è uno smart account ERC-4337 minimale protetto da
una passkey WebAuthn/secp256r1 invece che da una chiave ECDSA gestita con seed phrase. Combina tre
componenti già inclusi tramite `contracts/lib/openzeppelin-contracts` (nessuna nuova dipendenza):
`Account` di OZ (`validateUserOp` ERC-4337), `SignerWebAuthn` (verifica delle firme passkey) e
`ERC7821` (esecuzione batch minimale). Implementa anche ERC-1271, così si collega come wallet
membro di Registerwerk esattamente come qualsiasi altro wallet a smart contract. Viene deployato
come account proprio per ogni cliente e **non è un delegato EIP-7702**: senza passkey nello storage
proprio dell'account, ogni verifica di firma fallisce.

Il guardian è un argomento esplicito del costruttore, mai il deployer per errore. Le chiamate
possono essere classificate come ordinarie, di amministrazione o di recupero per destinazione e
selector. I batch EntryPoint/ERC-7821 rifiutano le operazioni di amministrazione e di recupero,
quindi una passkey di sessione compromessa o una UserOperation sponsorizzata non possono eseguirle.
`guardianExecute` è un **override custodiale completo**, non un percorso solo protettivo: il
guardian può eseguire qualsiasi chiamata dall'account senza timelock né co-firma della passkey, e
imposta da sé la tabella dei ruoli. Se un guardian detenuto dal registro con controllo unilaterale
sugli account retail sia voluto è una decisione di custodia aperta (autorizzazione e informativa).
Fino alla decisione, trattare la chiave del guardian come custodia degli asset dell'account.

Abbinato a `EwpgPaymaster`, il percorso di un investitore retail dall'onboarding alla prima
sottoscrizione non richiede né seed phrase né token di gas: autenticazione biometrica con passkey
più esecuzione sponsorizzata. Nota: `contracts/foundry.toml` ora abilita l'ottimizzatore Solidity
(`optimizer = true`, `optimizer_runs = 200`, in linea con il default della libreria OZ inclusa);
senza di esso il parsing delle firme WebAuthn genera «stack too deep».

I test (`contracts/test/ecosystem/EwpgPasskeyAccount.t.sol`) costruiscono vere asserzioni di
autenticazione WebAuthn con i cheatcode P256 nativi di Foundry (`vm.publicKeyP256`/`vm.signP256`),
incluso un esempio svolto dell'unica insidia non evidente: `abi.encode(structValue)` aggiunge una
parola di offset in più al livello superiore per uno struct con campi dinamici, che
`WebAuthn.tryDecodeAuth` non si aspetta; codificare invece i campi dello struct come argomenti
separati (vedere l'helper `_sign` del test e il suo commento).
`test_eip7702DelegateHasNoSignerAndFailsClosed` mostra che un EOA che delega a un'istanza non ha
firmatario e non può essere controllato da chi ha deployato l'istanza.

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
