---
title: Solana
description: Solana-Blockchain-Unterstützung – SPL- und SPL-2022-Token-Programme für Solana-native Wertpapiere.
---

# Solana { #solana }

Solana bietet einen hohen Durchsatz (50.000+ TPS), eine Finalität in unter einer Sekunde und sehr
niedrige Transaktionskosten. Registerwerk unterstützt Solana-native Wertpapier-Token sowohl über das
klassische **SPL Token**-Programm als auch über das erweiterte **Token-2022**-Programm (SPL-2022).

---

## Unterstützte Netzwerke { #supported-networks }

| Netzwerk | Network-Enum | Endpunkt | Verwendung |
|---|---|---|---|
| Solana mainnet-beta | `MAINNET` | `https://api.mainnet-beta.solana.com` | Produktion |
| Solana devnet | `TESTNET` | `https://api.devnet.solana.com` | Entwicklung/Test |

---

## Client-Bibliothek: Solanaj { #client-library-solanaj }

Registerwerk verwendet **Solanaj** (Java-Client-Bibliothek für Solana) über die `SolanaClientFactory`.
Kernoperationen:

| Operation | Solanaj-API | Verwendet in |
|---|---|---|
| Mint-Konto erstellen | `MintLayout.encode()` + `SystemProgram.createAccount()` | `SolanaTokenService.deploy()` |
| Token minten | Token-Programmanweisung `mintTo` | `SolanaTokenService.mint()` |
| Übertragen | Token-Programmanweisung `transfer` | `SolanaTokenService.transfer()` |
| Autorität festlegen | Token-Programmanweisung `setAuthority` | Admin-Operationen |
| Guthaben abrufen | `rpcClient.getTokenAccountBalance()` | Indexer, Wallet-Guthaben |

---

## Token-Kontomodell { #token-account-model }

Solanas Token-Modell unterscheidet sich erheblich von EVM:

- Ein **Mint-Konto** definiert den Token (entspricht einer ERC-20-Vertragsadresse)
- Jeder Inhaber benötigt ein separates **Token-Konto** (Associated Token Account, ATA), um den Token
  zu halten
- Der Bereitstellungsablauf von Registerwerk erzeugt automatisch ATAs für die Wallets des Betreibers
- Anleger-ATAs werden beim ersten Empfang angelegt

`AssetDeployment.contractAddress` speichert die Solana-**Mint-Adresse** (Base58-codierter
öffentlicher Schlüssel).

---

## SPL-2022-Erweiterungen { #spl-2022-extensions }

Eine ausführliche Behandlung der Token-2022-Erweiterungen (InterestBearing, ConfidentialTransfer,
TransferHook, PermanentDelegate) finden Sie unter [SPL-2022](../token-standards/spl-2022.md).

---

## Indexer { #indexer }

Der Solana-Indexer überwacht Transaktionen auf verfolgten Mint-Konten über WebSocket-Abonnements
(via Helius- oder Shyft-Enhanced-APIs). Bei jeder bestätigten Transaktion:

1. Das Transaktionsprotokoll wird nach Token-Transfer-Anweisungen durchsucht
2. Solana-Konten werden auf `LegalEntity`-Datensätze abgebildet (Absender/Empfänger)
3. Ein `token_transfer`-Datensatz wird geschrieben (konsistentes Schema mit dem EVM-Indexer)
4. `AssetHolder.nominalAmount` wird aktualisiert

Der `IndexerMonitorService` prüft alle 5 Minuten die Liveness des Solana-Indexers. Wird auf einem
aktiven Asset länger als 30 Minuten kein Ereignis empfangen, wird ein `DORA_AVAILABILITY`-Vorfall
eröffnet.

---

## Betreiber-Wallet auf Solana { #operator-wallet-on-solana }

Registerwerks Solana-Wallet ist ein Standard-**ed25519-Schlüsselpaar**. Der private Schlüssel wird
verschlüsselt im Wallet-Tresor des Betreibers gespeichert (derselbe KMS/KEK-Umschlag wie bei den
EVM-Keystores). Die Betreiber-Wallet ist Mint-Authority und Freeze-Authority für alle SPL-2022-Token.

!!! warning "SOL-Guthaben für Rent"
    Solana-Konten benötigen **Rent** (Mindest-SOL-Guthaben), um geöffnet zu bleiben. Vom
    Bereitstellungsdienst eröffnete Token-Konten erfordern eine kleine SOL-Einzahlung. Der
    `WalletBalanceService` überwacht das SOL-Guthaben des Betreibers und warnt, wenn es unter
    0,5 SOL fällt.
