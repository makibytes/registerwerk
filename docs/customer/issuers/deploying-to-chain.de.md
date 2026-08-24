---
title: Auf die Blockchain ausbringen
---

# Auf die Blockchain ausbringen

Sobald der Registerbetreiber Ihre Emission genehmigt hat, können Sie den Token-Contract auf die Blockchain ausbringen. Dieser Schritt ist unumkehrbar — die Contract-Adresse wird Teil der dauerhaften Registeraufzeichnung.

## Voraussetzungen

- Der Status der Emission ist **APPROVED**
- Sie haben die Rolle **Issuer** oder **Company Admin**
- Bei ERC-3643-Emissionen: Der Betreiber hat die Factory-Contracts auf der Zielchain bereits ausgebracht

## Die Ausbringung starten

1. Gehen Sie zu **Issuances** und suchen Sie Ihre Emission (Status: APPROVED)
2. Klicken Sie **Deploy to Blockchain**
3. Ein Bestätigungsdialog erscheint und fasst die Ausbringungsparameter zusammen:

| Parameter | Wert |
|-----------|-------|
| Token standard | ERC-3643 |
| Network | Polygon Mainnet |
| ISIN | DE000EXAMPLE0 |
| Name | Example AG Bond 2025 |
| Symbol | EAGB25 |
| Total supply | 10.000.000 |

4. Klicken Sie **Confirm Deployment**

## Was während der Ausbringung geschieht

Das Register-Backend sendet die Ausbringungstransaktion in Ihrem Namen über eine vom Betreiber kontrollierte Deployer-Wallet an die Blockchain. Sie müssen selbst keine Transaktion signieren und kein ETH/MATIC halten.

Bei einer **ERC-3643**-Emission werden folgende Contracts nacheinander ausgebracht:

1. **Token-Contract** — der eigentliche ERC-3643-Token
2. **Identity Registry** — ordnet Anleger-Wallet-Adressen ihrer ONCHAINID zu
3. **Identity Registry Storage** — dauerhafter Speicher für die Registry
4. **Claim Topics Registry** — listet die erforderlichen KYC-Claim-Themen auf (z. B. Thema 1 = KYC, Thema 2 = AML)
5. **Trusted Issuers Registry** — listet auf, welchen Identitätsausstellern beim Ausstellen von Claims vertraut wird
6. **Modular Compliance** — Container für Compliance-Regelmodule

Das dauert typischerweise 30–120 Sekunden, je nach Netzauslastung.

## Den Fortschritt verfolgen

Die Detailseite der Emission zeigt während der Ausbringung eine laufende Fortschrittsanzeige. Jede Contract-Ausbringung wird mit ihrem Transaktions-Hash aufgeführt, der zum Block-Explorer verlinkt.

Scheitert ein Schritt (etwa wegen einer Netzstörung oder unzureichendem Gas), wird die Ausbringung automatisch bis zu dreimal wiederholt. Scheitern alle Versuche, kehrt die Emission in den Status **APPROVED** zurück, und Sie werden per E-Mail benachrichtigt.

## Nach erfolgreicher Ausbringung

Sind alle Contracts ausgebracht, wechselt die Emission in den Status **ISSUED**. Sie sehen dann:

- **Contract-Adresse** — die Adresse des Haupt-Token-Contracts
- **Block-Explorer-Link** — den Contract auf Etherscan, Polygonscan usw. prüfen
- **Ausbringungstransaktion** — die Transaktion, die den Token erzeugt hat

!!! tip
    Geben Sie Contract-Adresse und Explorer-Link an Ihre Anleger weiter, damit diese ihre Bestände unabhängig überprüfen können.


## Nächste Schritte

- [Anleger hinzufügen und Wallets zulassen](./managing-investors.md)
- Compliance-Module einrichten (bei Standard-ERC-3643-Konfigurationen erledigen das die Betreiber automatisch)
- Die Emission Ihren Anlegern ankündigen
