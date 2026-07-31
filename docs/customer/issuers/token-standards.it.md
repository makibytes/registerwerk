---
title: Scegliere uno standard di token
---

# Scegliere uno standard di token

Il registro eWpG supporta cinque standard di token. Questa pagina ti aiuta a capire le differenze e a scegliere quello giusto per la tua emissione.

## ERC-20 — Token fungibile

ERC-20 è lo standard di token più diffusamente supportato sulle chain compatibili con Ethereum. Tutti i token della stessa classe sono identici e intercambiabili.

**Pro**
- Supportato praticamente da ogni wallet, exchange e protocollo DeFi
- Semplice da distribuire e gestire
- Basso costo di gas per i trasferimenti

**Contro**
- Nessuna conformità applicata nativamente — chiunque può ricevere il token
- Nessun supporto nativo per importi parziali negli strumenti frazionati

**Ideale per**: strumenti fungibili la cui conformità è gestita interamente off-chain, o distribuzioni di prova interne.

---

## ERC-721 — Token non fungibile (NFT)

I token ERC-721 sono unici — ogni token ha un identificativo e un proprietario distinti. Ciò li rende adatti a strumenti che rappresentano un bene unico o un'unità determinata.

**Pro**
- Ogni token è identificabile singolarmente (utile per titoli di debito con condizioni proprie)
- Metadati ricchi tramite `tokenURI`
- Forte supporto da parte di wallet e marketplace

**Contro**
- Non adatto a grandi quantità di unità fungibili (una transazione per token)
- Costo di gas per trasferimento più alto rispetto a ERC-20

**Ideale per**: strumenti unici, singole obbligazioni o prodotti strutturati in cui ogni unità ha condizioni proprie.

---

## ERC-1155 — Standard multi-token

ERC-1155 consente a un solo contratto di gestire contemporaneamente più tipi di token — sia fungibili sia non fungibili.

**Pro**
- Operazioni in blocco efficienti: trasferire più tipi di token in un'unica transazione
- Può rappresentare in un solo contratto strumenti fungibili e non fungibili
- Costo di gas inferiore per le operazioni in blocco rispetto a più contratti ERC-20/721

**Contro**
- Meno diffusamente supportato dai wallet al dettaglio rispetto a ERC-20 o ERC-721
- Nessuna conformità applicata nativamente

**Ideale per**: emittenti che gestiscono più tranche o serie di strumenti e vogliono ridurre la complessità contrattuale.

---

## ERC-3643 (T-REX) — Consigliato per gli strumenti regolamentati

ERC-3643, noto anche come T-REX (Token for Regulated EXchanges), è uno standard aperto progettato specificamente per i token rappresentativi di strumenti finanziari regolamentati. È lo **standard consigliato** per la maggior parte delle emissioni sotto l'eWpG.

**Pro**
- Conformità on-chain: i trasferimenti sono bloccati automaticamente se una delle parti non supera i controlli
- L'identità dell'investitore è verificata tramite ONCHAINID, uno standard di identità decentralizzata
- Moduli di conformità granulari (saldo massimo, numero massimo di investitori, restrizioni per Paese, ecc.)
- Separazione dei ruoli di agente (identity agent, transfer agent, compliance agent)
- Pienamente compatibile con i protocolli DeFi che supportano l'interfaccia ERC-20

**Contro**
- Configurazione iniziale più complessa (richiede la distribuzione di più contratti)
- Gli investitori devono avere un ONCHAINID e claim KYC/antiriciclaggio validi prima di ricevere token
- Costo di gas per trasferimento leggermente superiore a causa dei controlli di conformità

**Ideale per**: qualsiasi emissione di strumento regolamentato in cui le restrizioni al trasferimento devono essere applicate automaticamente on-chain.

Vedi l'approfondimento completo su [ERC-3643 spiegato](../../token-standards/erc3643.md).

---

## ERC-3643 confidenziale — Token regolamentati che tutelano la riservatezza

L'ERC-3643 confidenziale estende lo standard T-REX con la crittografia completamente omomorfica (FHE), fornita dal fhEVM di Zama. Saldi e importi trasferiti sono cifrati on-chain — solo le parti autorizzate possono decifrarli.

**Pro**
- I saldi degli investitori restano nascosti al pubblico pur rimanendo verificabili dalle parti autorizzate
- La conformità resta pienamente applicata (lo smart contract può verificarla su dati cifrati)
- Adatto a casi d'uso istituzionali in cui l'ampiezza delle posizioni deve restare riservata

**Contro**
- Disponibile solo sulle reti Fhenix e Inco
- Costo di gas più alto per il calcolo FHE
- Supporto di wallet e strumenti più limitato rispetto all'ERC-3643 standard
- Gli investitori hanno bisogno di strumenti wallet compatibili con FHE per interagire

**Ideale per**: strumenti istituzionali in cui la riservatezza delle posizioni è un requisito regolamentare o commerciale.

Vedi [I token confidenziali spiegati](../../token-standards/confidential.md).

---

## Guida alla decisione

```
Is on-chain compliance enforcement required?
  YES → Are balances required to be confidential?
            YES → Confidential ERC-3643
            NO  → ERC-3643 (T-REX)
  NO  → Are tokens unique/non-fungible?
            YES → ERC-721
            NO  → Do you need multiple token types in one contract?
                      YES → ERC-1155
                      NO  → ERC-20
```
