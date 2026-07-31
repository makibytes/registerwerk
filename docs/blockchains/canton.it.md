---
title: Canton / DAML Ledger
description: Registro privato di Canton e integrazione DAML Finance per strumenti obbligazionari regolamentati.
---

# Canton / DAML Ledger { #canton-daml-ledger }

Canton è un **registro distribuito incentrato sulla privacy** sviluppato da Digital Asset. A differenza delle blockchain pubbliche, Canton implementa la **privacy per sub-transazione**: ogni partecipante vede solo i contratti di cui è parte. Ciò rende Canton attraente per strumenti istituzionali in cui le posizioni non dovrebbero essere visibili ad altri partecipanti al mercato.

---

## Concetti architettonici di Canton { #canton-architecture-concepts }

| Concetto | Canton | Mappatura Registerwerk |
|---|---|---|
| **Ledger** | Il registro distribuito di Canton | Un nodo partecipante alla Canton Network per operatore |
| **Party** | Un'identità crittografica univoca sul registro | `LegalEntity.cantonPartyId` |
| **Contratto** | Un'istanza del contratto DAML | Uno per posizione obbligazionaria o patrimoniale |
| **Scelta** | L'azione esercitabile sul contratto | Azione societaria (cedola, rimborso) |
| **Sincronizzatore** | La componente del consenso | Sincronizzatore globale Canton Network |
| **Ledger API** | gRPC API per interagire con Canton | `CantonLedgerEndpoint` |

---

## Tipi di obbligazioni DAML Finance { #daml-finance-bond-types }

Vedere [DAML Finance Bonds](../token-standards/canton-daml.md) per il trattamento completo della configurazione dei termini obbligazionari e dei pagamenti di cedola.

---

## Configurazione della connessione { #connection-configuration }

Il `CantonLedgerEndpoint` si connette a un nodo partecipante Canton tramite la sua **Ledger API** (gRPC):

```yaml
registerwerk:
  canton:
    mainnet:
      ledgerApiUrl: "participant.example.com:5001"
      synchronizerId: "global-synchronizer"
      applicationId: "registerwerk"
      authToken: "${CANTON_MAINNET_TOKEN}"  # JWT for participant auth
    devnet:
      ledgerApiUrl: "localhost:5001"
      synchronizerId: "dev-synchronizer"
```

Per Canton Network (Canton pubblico): ottenere un nodo partecipante dall'operatore Canton Network, registrare la propria applicazione e fornire l'URL della Ledger API.

Per lo sviluppo: un sandbox locale Canton è disponibile tramite `docker compose -f indexer/canton/docker-compose.yml up`.

---

## Assegnazione della Party { #party-allocation }

Prima che un cliente possa partecipare a strumenti basati su Canton, gli deve essere assegnata una **Canton Party**. Questo è gestito da `CantonPartyAllocator.allocate(entityId)`:

1. Richiama la Ledger API `PartyManagementService.allocateParty()`
2. Memorizza l'identificatore della parte restituito in `LegalEntity.cantonPartyId`
3. L'identificatore della parte viene utilizzato in tutti i riferimenti contrattuali DAML per tale entità

Le parti sono immutabili una volta assegnate; una parte non può mai essere riutilizzata per un'entità diversa.

---

## Modello di privacy { #privacy-model }

La privacy di Canton viene applicata a livello di registro:

- **L'emittente** vede: tutti i contratti per i suoi strumenti
- **L'investitore** vede: solo i propri contratti di posizione
- **Operatore del registro** vede: tutti i contratti (come ruolo di osservatore DAML)
- **Altri investitori**: non possono vedere le posizioni di altri investitori

Questa è privacy nativa senza crittografia: l'infrastruttura del registro garantisce che i dati del contratto vengano trasmessi solo alle parti interessate in tale contratto.

---

## Il profilo Maven `-Pcanton` { #the-pcanton-maven-profile }

Poiché DAML SDK e i JAR associati sono grandi e non su Maven Central, il supporto Canton è protetto dietro il profilo `-Pcanton`:

```bash
./mvnw verify -Pcanton          # includes Canton
./mvnw verify                   # Canton disabled, stub injected
```

In assenza di `-Pcanton`, viene utilizzato `CantonBondDisabledStub`. Le chiamate API agli strumenti basati su Canton restituiscono `503 Service Unavailable` con un messaggio che spiega che il supporto Canton richiede il profilo `-Pcanton` e un nodo partecipante in esecuzione.

---

## Indicizzatore { #indexer }

L'indicizzatore Canton utilizza il **Servizio di transazione** della Ledger API per eseguire lo streaming di tutte le transazioni impegnate. Elabora:
- Contratti di emissione di obbligazioni → crea record `AssetHolder`
- Eventi di pagamento cedola → crea record `token_transfer` di tipo `COUPON`
- Eventi di trasferimento → aggiorna `AssetHolder.nominalAmount`

La vivacità dell'indicizzatore Canton è monitorata da `IndexerMonitorService`, come gli indicizzatori EVM e Solana.
