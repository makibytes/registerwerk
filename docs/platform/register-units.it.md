---
title: Unità del registro
description: Il registro conta unità intere - come vengono distribuiti i token di obbligazioni e fondi (decimals = 0), cosa rifiuta su qualsiasi altro token e cosa fare.
---

# Unità del registro { #register-units }

**Il registro conta unità intere. Un token è un'unità del titolo.**

Gli importi del registro - il valore nominale di un titolare e ogni trasferimento indicizzato - sono le **unità
di base grezze** del token; l'indicizzatore le scrive senza scalare. Il calcolo di cedole e rimborso
(`amountPerUnit x nominal`), il mint sul mercato primario (l'importo assegnato viene inviato al token così com'è)
e la negoziazione secondaria (quantità e prezzo per unità) leggono questi importi come unità intere. Su un token a
18 decimali ciascuno sarebbe sbagliato di un fattore 10^18. Il registro quindi non scala: distribuisce i token di
obbligazioni e fondi con `decimals = 0` e rifiuta ognuna di queste operazioni su un token che non conta in unità
intere.

## Cosa viene distribuito { #what-is-deployed }

| Standard | Decimali di una nuova distribuzione |
|---|---|
| ERC-20 (`EwpgERC20`), ERC-3643 (T-REX), ERC-721, ERC-1155, ERC-3525 (EVM e Starknet), SPL / Token-2022, obbligazioni Daml | **0** |
| ERC-20 Starknet (Cairo, fisso a 18), asset Stellar (fisso a 7) | come definito dal contratto - **rifiutati** dalle operazioni seguenti |
| Quote di vault ERC-4626 / ERC-7540 (seguono il sottostante), token confidenziali, token Canton | fuori dal controllo del registro - registrati come *sconosciuti*, **rifiutati** |

I decimali sono registrati sulla distribuzione (`asset_deployment.token_decimals`). Le distribuzioni precedenti a
questa regola mantengono ciò che il codice precedente aveva distribuito (ad esempio 18 per ERC-20 ed ERC-3643) e
vengono quindi rifiutate anch'esse.

## Cosa viene rifiutato { #what-is-refused }

Ogni asset con una distribuzione attiva (in attesa o confermata) i cui decimali non siano esattamente 0 - inclusi
quelli sconosciuti - viene rifiutato, in modalità fail-closed, con un `409` che indica la distribuzione e i suoi
decimali:

- **Operazioni societarie** (cedole, rimborso, dividendi, frazionamenti, richiami): lo snapshot alla data di
  registrazione non viene eseguito; l'operazione è parcheggiata come `SNAPSHOT_BLOCKED` con il motivo, auditata,
  segnalata e ritentata ogni giorno.
- **Sottoscrizioni**: assegnazione e regolamento (il mint).
- **Negoziazione**: creazione di un'offerta, acquisto e regolamento di un'operazione.
- **Rimborso (annullamento)**: avvio del rimborso del titolo (gli importi annullati sono le unità di base grezze del registro).

Un asset senza distribuzione (registro off-chain) non ha nulla da scalare e non è interessato.

!!! warning "Come correggere un asset rifiutato"
    Il token non può essere modificato sul posto. Distribuite di nuovo l'asset con un token in unità intere
    (`decimals = 0`) e trasferite il registro su di esso. Nel frattempo nulla viene pagato, coniato o negoziato.
