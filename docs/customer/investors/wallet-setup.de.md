---
title: Wallet einrichten
---

# Wallet einrichten

Um Wertpapier-Token zu halten und einzusehen, müssen Sie eine Blockchain-Wallet mit Ihrem Registerkonto verbinden. Diese Seite erklärt, wie Sie eine kompatible Wallet einrichten und für ERC-3643-Token zulassen lassen.

## Unterstützte Wallet-Typen

Das eWpG-Register unterstützt jede selbstverwahrte Wallet, die EIP-712-Signaturen erzeugen kann. Empfohlene Wallets:

| Wallet | Typ | Netze |
|--------|------|----------|
| MetaMask | Browser-Erweiterung / mobil | Alle EVM-Netze |
| Ledger Live | Hardware | Alle EVM-Netze |
| Trezor Suite | Hardware | Alle EVM-Netze |
| Phantom | Browser-Erweiterung / mobil | Solana (und EVM) |
| Rabby | Browser-Erweiterung | Alle EVM-Netze |

!!! tip
    Für den institutionellen Einsatz sind Hardware-Wallets (Ledger, Trezor) nachdrücklich zu empfehlen. Sie halten Ihren privaten Schlüssel offline und verlangen für jede Transaktion eine physische Bestätigung.


## Eine Wallet verbinden

1. Gehen Sie zu **Profile → Wallets**
2. Klicken Sie **Connect Wallet**
3. Wählen Sie Ihren Wallet-Typ aus der Liste
4. Ihre Wallet-Erweiterung öffnet sich und bittet um die Verbindung. Bestätigen Sie sie.
5. Das Portal bittet Sie, **eine Nachricht zu signieren** — eine gasfreie Signatur, die den Besitz der Wallet-Adresse belegt. Signieren Sie sie in Ihrer Wallet.
6. Die Wallet-Adresse erscheint nun in Ihrer Wallet-Liste.

Sie können mehrere Wallets verbinden. Die Bestände aller verbundenen Wallets werden in der Ansicht **Investments** zusammengeführt.

## Zulassung für ERC-3643-Token

Eine Wallet mit dem Portal zu verbinden lässt sie nicht automatisch für ERC-3643-Übertragungen zu. Die Zulassung ist ein eigener Schritt, den der **Emittent** des Tokens vornimmt, nachdem er Ihren KYC-Status geprüft hat.

Der Ablauf:

1. Verbinden Sie Ihre Wallet im Portal (wie oben beschrieben)
2. Teilen Sie dem Emittenten Ihre Wallet-Adresse mit (auf der Seite **Wallets** sichtbar)
3. Stellen Sie sicher, dass Ihre KYC/AML-Prüfung abgeschlossen ist (siehe **Profile → Identity**)
4. Der Emittent trägt Ihre Wallet in seinen Identity-Registry-Contract ein
5. Sie erhalten eine Benachrichtigung, wenn die Zulassung erfolgt ist

Nach der Zulassung können Sie an dieser Wallet-Adresse Token empfangen. Die Zulassung ist on-chain gespeichert und besteht unabhängig vom Portal fort.

## Eine Wallet entfernen

So entfernen Sie eine Wallet aus Ihrem Konto:

1. Gehen Sie zu **Profile → Wallets**
2. Klicken Sie **Remove** neben der Wallet-Adresse

Eine Wallet aus Ihrem Portalkonto zu entfernen entfernt sie nicht aus der On-Chain-Zulassungsliste eines Emittenten. Wenden Sie sich an jeden Emittenten einzeln, wenn Ihre Adresse aus dessen Identity Registry entfernt werden soll.

## Eine Solana-Wallet hinzufügen

Für Solana-basierte Token:

1. Gehen Sie zu **Profile → Wallets**
2. Klicken Sie **Connect Wallet → Solana**
3. Verbinden Sie über Phantom oder eine andere unterstützte Solana-Wallet
4. Signieren Sie die Verifizierungsnachricht

Solana-Wallet-Adressen nutzen ein anderes Format (base58) als EVM-Wallets. Das Portal zeigt der Klarheit halber beide Formate nebeneinander an.

## Bewährte Sicherheitspraxis

- **Geben Sie Ihren privaten Schlüssel niemals weiter** — auch nicht an den Registerbetreiber
- Nutzen Sie eine eigene Wallet für Wertpapiere; vermischen Sie sie nicht mit privater DeFi-Aktivität
- Aktivieren Sie Passwort- bzw. biometrischen Schutz der Wallet
- Sichern Sie Ihre Seed-Phrase an einem sicheren Ort offline
- Nutzen Sie bei erheblichen Beständen eine Hardware-Wallet

!!! warning
    Der Registerbetreiber wird niemals nach Ihrem privaten Schlüssel oder Ihrer Seed-Phrase fragen. Fragt jemand danach und gibt vor, vom Register zu sein, ist es Betrug — geben Sie nichts heraus und melden Sie es sofort.

