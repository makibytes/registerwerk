---
title: Den Token-Standard wählen
---

# Den Token-Standard wählen

Das eWpG-Register unterstützt fünf Token-Standards. Diese Seite hilft Ihnen, die Unterschiede zu verstehen und den richtigen für Ihre Emission zu wählen.

## ERC-20 — Fungibler Token

ERC-20 ist der am breitesten unterstützte Token-Standard auf Ethereum-kompatiblen Chains. Alle Token derselben Klasse sind identisch und untereinander austauschbar.

**Vorteile**
- Von praktisch jeder Wallet, Börse und jedem DeFi-Protokoll unterstützt
- Einfach auszubringen und zu verwalten
- Geringe Gas-Kosten bei Übertragungen

**Nachteile**
- Keine eingebaute Compliance-Durchsetzung — jeder kann den Token empfangen
- Keine native Unterstützung für Teilbeträge bei fraktionierten Wertpapieren

**Am besten für**: Fungible Wertpapiere, deren Compliance vollständig off-chain gesteuert wird, oder interne Testausbringungen.

---

## ERC-721 — Nicht-fungibler Token (NFT)

ERC-721-Token sind einzigartig — jeder Token hat eine eigene ID und einen eigenen Inhaber. Damit eignen sie sich für Wertpapiere, die einen einzigartigen Vermögenswert oder eine bestimmte Einheit verkörpern.

**Vorteile**
- Jeder Token ist einzeln identifizierbar (nützlich für Schuldverschreibungen mit individuellen Konditionen)
- Reichhaltige Metadaten über `tokenURI`
- Starke Unterstützung durch Wallets und Marktplätze

**Nachteile**
- Ungeeignet für große Mengen fungibler Einheiten (eine Transaktion je Token)
- Höhere Gas-Kosten je Übertragung als bei ERC-20

**Am besten für**: Einzigartige Wertpapiere, einzelne Anleihen oder strukturierte Produkte, bei denen jede Einheit eigene Konditionen hat.

---

## ERC-1155 — Multi-Token-Standard

ERC-1155 erlaubt es, mit einem einzigen Contract mehrere Token-Typen gleichzeitig zu verwalten — fungible wie nicht-fungible.

**Vorteile**
- Effiziente Sammeloperationen: mehrere Token-Typen in einer Transaktion übertragen
- Kann fungible und nicht-fungible Wertpapiere in einem Contract abbilden
- Geringere Gas-Kosten bei Sammeloperationen als mehrere ERC-20/721-Contracts

**Nachteile**
- Weniger breit von Endkunden-Wallets unterstützt als ERC-20 oder ERC-721
- Keine eingebaute Compliance-Durchsetzung

**Am besten für**: Emittenten, die mehrere Tranchen oder Serien verwalten und die Contract-Komplexität senken wollen.

---

## ERC-3643 (T-REX) — Empfohlen für regulierte Wertpapiere

ERC-3643, auch bekannt als T-REX (Token for Regulated EXchanges), ist ein offener Standard, der eigens für regulierte Wertpapier-Token entworfen wurde. Er ist der **empfohlene Standard** für die meisten Emissionen unter dem eWpG.

**Vorteile**
- On-Chain-Compliance: Übertragungen werden automatisch verhindert, wenn eine Seite die Prüfungen nicht besteht
- Die Identität der Anleger wird über ONCHAINID verifiziert, einen dezentralen Identitätsstandard
- Feingranulare Compliance-Module (Höchstbestand, Höchstzahl Anleger, Länderbeschränkungen usw.)
- Trennung der Agentenrollen (Identity Agents, Transfer Agents, Compliance Agents)
- Vollständig kompatibel mit DeFi-Protokollen, die das ERC-20-Interface unterstützen

**Nachteile**
- Komplexere Ersteinrichtung (mehrere Contracts müssen ausgebracht werden)
- Anleger brauchen eine ONCHAINID und gültige KYC/AML-Claims, bevor sie Token empfangen können
- Etwas höhere Gas-Kosten je Übertragung wegen der Compliance-Prüfungen

**Am besten für**: Jede Emission eines regulierten Wertpapiers, bei der Übertragungsbeschränkungen automatisch on-chain durchgesetzt werden müssen.

Die vollständige Vertiefung finden Sie unter [ERC-3643 erklärt](../../token-standards/erc3643.md).

---

## Vertrauliches ERC-3643 — Regulierte Token mit Privatsphäre

Vertrauliches ERC-3643 erweitert den T-REX-Standard um vollhomomorphe Verschlüsselung (FHE), bereitgestellt durch Zamas fhEVM. Token-Bestände und Übertragungsbeträge sind on-chain verschlüsselt — nur berechtigte Stellen können sie entschlüsseln.

**Vorteile**
- Anlegerbestände bleiben der Öffentlichkeit verborgen und für Berechtigte dennoch prüfbar
- Die vollständige Compliance-Durchsetzung bleibt erhalten (der Smart Contract kann Compliance auf verschlüsselten Daten prüfen)
- Geeignet für institutionelle Anwendungsfälle, in denen Positionsgrößen vertraulich bleiben müssen

**Nachteile**
- Nur in den Netzen Fhenix und Inco verfügbar
- Höhere Gas-Kosten durch die FHE-Berechnung
- Weniger Unterstützung durch Wallets und Werkzeuge als beim gewöhnlichen ERC-3643
- Anleger benötigen FHE-fähige Wallet-Werkzeuge zur Interaktion

**Am besten für**: Institutionelle Wertpapiere, bei denen die Vertraulichkeit der Bestände eine aufsichtsrechtliche oder geschäftliche Anforderung ist.

Siehe [Vertrauliche Token erklärt](../../token-standards/confidential.md).

---

## Entscheidungshilfe

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
