---
title: Register-Einheiten
description: Das Register zählt ganze Einheiten - wie Anleihe- und Fonds-Token bereitgestellt werden (decimals = 0), was die Registerführung bei jedem anderen Token verweigert und was zu tun ist.
---

# Register-Einheiten { #register-units }

**Das Register zählt ganze Einheiten. Ein Token ist eine Einheit des Wertpapiers.**

Die Beträge des Registers - der Nominalbetrag eines Inhabers und jede indexierte Übertragung - sind die
**rohen Basiseinheiten** des Tokens; der Indexer schreibt sie ohne Skalierung. Kupon- und Rückzahlungsrechnung
(`amountPerUnit x nominal`), der Primärmarkt-Mint (der zugeteilte Betrag wird unverändert an den Token gesendet)
und der Sekundärhandel (Menge und Preis je Einheit) lesen diese Beträge als ganze Einheiten. Bei einem Token mit
18 Dezimalstellen wäre jeder davon um den Faktor 10^18 falsch. Die Registerführung skaliert deshalb nicht: Sie
stellt Anleihe- und Fonds-Token mit `decimals = 0` bereit und verweigert jeden dieser Abläufe bei einem Token, der
nicht in ganzen Einheiten zählt.

## Was bereitgestellt wird { #what-is-deployed }

| Standard | Dezimalstellen einer neuen Bereitstellung |
|---|---|
| ERC-20 (`EwpgERC20`), ERC-3643 (T-REX), ERC-721, ERC-1155, ERC-3525 (EVM und Starknet), SPL / Token-2022, Daml-Anleihen | **0** |
| Starknet-ERC-20 (Cairo, fest 18), Stellar-Assets (fest 7) | wie vom Vertrag vorgegeben - von den folgenden Abläufen **verweigert** |
| ERC-4626 / ERC-7540 Vault-Anteile (folgen dem Basiswert), vertrauliche Token, Canton-Token | nicht in der Hand der Registerführung - als *unbekannt* erfasst und **verweigert** |

Die Dezimalstellen werden an der Bereitstellung gespeichert (`asset_deployment.token_decimals`). Bereitstellungen
aus der Zeit vor dieser Regel behalten, was der frühere Code bereitgestellt hat (z. B. 18 für ERC-20 und
ERC-3643) und werden deshalb ebenfalls verweigert.

## Was verweigert wird { #what-is-refused }

Jedes Asset mit einer aktiven (ausstehenden oder bestätigten) Bereitstellung, deren Dezimalstellen nicht genau 0
sind - auch unbekannte - wird fail-closed mit einem `409` abgewiesen, der die Bereitstellung und ihre
Dezimalstellen nennt:

- **Kapitalmaßnahmen** (Kupons, Rückzahlung, Dividenden, Splits, Kündigungen): Der Stichtags-Snapshot wird nicht
  erstellt; die Maßnahme wird mit Begründung als `SNAPSHOT_BLOCKED` geparkt, protokolliert, alarmiert und täglich
  erneut versucht.
- **Zeichnungen**: Zuteilung und Abwicklung (der Mint).
- **Handel**: Anlegen eines Angebots, Kauf und Abwicklung eines Handels.
- **Rückzahlung (Einziehung)**: Start der Rückzahlung des Wertpapiers (die Einziehungsbeträge sind die rohen Basiseinheiten des Registers).

Ein Asset ohne Bereitstellung (Off-Chain-Register) hat nichts zu skalieren und ist nicht betroffen.

!!! warning "So wird ein verweigertes Asset behoben"
    Der Token lässt sich nicht an Ort und Stelle ändern. Stellen Sie das Asset mit einem Token in ganzen Einheiten
    (`decimals = 0`) neu bereit und übertragen Sie das Register darauf. Bis dahin wird nichts ausgezahlt,
    gemintet oder gehandelt.
