---
title: Anleger verwalten
---

# Anleger verwalten

Diese Anleitung erklärt, wie Sie Anleger zu Ihrer Emission hinzufügen, deren Wallets zulassen und deren ONCHAINID für ERC-3643-Token verwalten.

## Einen Anleger hinzufügen

Anleger müssen zunächst als Rechtsträger im eWpG-Register registriert sein. Ist Ihr Anleger noch nicht im System, wenden Sie sich zwecks Onboarding an den Registerbetreiber.

Sobald ein Anleger-Rechtsträger im Register existiert:

1. Gehen Sie zu Ihrer Emission und klicken Sie **Investors → Add Investor**
2. Suchen Sie den Anleger über Name, E-Mail oder Entity-ID
3. Wählen Sie den Anleger aus und klicken Sie **Add**

Der Anleger ist nun in der Registerdatenbank mit Ihrer Emission verknüpft. Bei **Simple**-Token (ERC-20/721/1155) genügt das — Sie können Token direkt an seine Wallet übertragen.

Bei **Control**-Token (ERC-3643) müssen Sie zusätzlich die Wallet des Anlegers zulassen (siehe unten).

## Wallets zulassen (ERC-3643)

ERC-3643-Token setzen durch, dass nur zugelassene, KYC-geprüfte Anleger Token empfangen können. Die Zulassungsliste liegt on-chain im Contract **Identity Registry**.

### Schritt 1 — Der Anleger nennt seine Wallet-Adresse

Der Anleger verbindet seine Wallet im Kundenportal unter **Wallets → Connect Wallet** (siehe [Wallet einrichten](../investors/wallet-setup.md)) und teilt Ihnen die Wallet-Adresse mit.

### Schritt 2 — Prüfen, ob der Anleger eine ONCHAINID hat

Jeder ERC-3643-Anleger braucht eine **ONCHAINID** — einen Smart Contract, der als seine On-Chain-Identität dient. Das Register legt sie beim Onboarding des Anleger-Rechtsträgers automatisch an.

Sie können das unter **Investor → [Name] → ONCHAINID** prüfen. Existiert sie, wird die Adresse des ONCHAINID-Contracts angezeigt.

### Schritt 3 — KYC/AML-Claims prüfen

ERC-3643-Token verlangen, dass Anleger gültige **Claims** auf ihrer ONCHAINID halten — kryptografische Bestätigungen, ausgestellt von einem vertrauenswürdigen KYC-Anbieter. Ihre Emission verlangt mindestens:

- **Claim-Thema 1**: KYC (Know Your Customer)
- **Claim-Thema 2**: AML (Geldwäscheprävention)

Der Registerbetreiber stellt diese Claims aus, nachdem der Anleger die KYC-Prüfung durchlaufen hat. Den Claim-Status sehen Sie auf der Detailseite des Anlegers.

!!! warning
    Sie können keinen Anleger zulassen, dessen ONCHAINID keine gültigen KYC/AML-Claims trägt. Ein Versuch wird von der On-Chain-Identity-Registry abgewiesen.


### Schritt 4 — Die Wallet in der Identity Registry eintragen

Sobald der Anleger eine gültige ONCHAINID und Claims hat:

1. Gehen Sie zu Ihrer Emission → **Investors → [Name des Anlegers]**
2. Klicken Sie **Add Wallet**
3. Tragen Sie die vom Anleger genannte Wallet-Adresse ein
4. Klicken Sie **Register on Chain**

Das Register-Backend sendet eine Transaktion an den Identity-Registry-Contract, die die Wallet-Adresse mit der ONCHAINID des Anlegers verknüpft. Das dauert typischerweise 5–15 Sekunden.

Nach der Eintragung ist die Wallet zugelassen. Der Anleger kann nun an dieser Adresse Token empfangen.

## Einen Anleger entfernen

So entfernen Sie die Wallet eines Anlegers aus der Zulassungsliste:

1. Gehen Sie zu **Investors → [Name des Anlegers] → Wallets**
2. Klicken Sie **Remove from Whitelist** neben der Wallet-Adresse
3. Bestätigen Sie die Aktion

Das Register sendet eine Transaktion, die die Wallet aus der Identity Registry entfernt. Der Anleger kann dann keine Token mehr empfangen, und jede künftige Übertragung an diese Wallet wird vom Smart Contract automatisch abgewiesen.

!!! note
    Einen Anleger aus der Zulassungsliste zu entfernen zieht seinen bestehenden Token-Bestand nicht ein. Müssen Token zurückgeholt werden (etwa aufgrund eines Gerichtsbeschlusses), wenden Sie sich an den Registerbetreiber — das erfordert eine Zwangsübertragung durch den Token-Agenten.


## Compliance-Module

Bei ERC-3643-Token konfiguriert der Betreiber Compliance-Module, die zusätzliche Regeln automatisch durchsetzen:

| Modul | Beschreibung |
|--------|-------------|
| **MaxBalance** | Begrenzt den höchsten Token-Bestand, den ein einzelner Anleger halten darf |
| **MaxInvestors** | Deckelt die Gesamtzahl verschiedener Anleger |
| **CountryRestrict** | Sperrt Anleger aus bestimmten Jurisdiktionen |

Diese Module laufen bei jedem Übertragungsversuch automatisch. Würde eine Übertragung eine Modulregel verletzen, wird sie on-chain abgewiesen, ohne dass Sie etwas tun müssen.

Wenden Sie sich an den Registerbetreiber, wenn Sie Modulparameter für Ihre Emission anpassen müssen.
