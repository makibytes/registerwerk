---
title: Häufige Fragen
---

# Häufige Fragen

## Allgemein

### Was ist das eWpG-Register?

Registerwerk ist eine Referenzimplementierung zum Anlegen und Verwalten von Aufzeichnungen über elektronische Wertpapiere und zugehörige Blockchain-Token. Ob ein Instrument nach dem deutschen Gesetz über elektronische Wertpapiere (eWpG) rechtlich anerkannt ist, hängt vom Instrument, vom Registermodell, vom Betreiber und von der Installation ab und muss extern geprüft werden.

### Ist das Register reguliert?

Die Erlaubnis ist installations- und betreiberspezifisch. Dieses Repository enthält keinen Nachweis, dass ein bestimmter Betreiber über eine erforderliche aufsichtsrechtliche Erlaubnis verfügt. Prüfen Sie die beabsichtigten Tätigkeiten, die Erlaubnisse des Betreibers und die Struktur des Instruments vor einer Nutzung mit qualifizierter Rechtsberatung und dem jeweiligen Betreiber.

### Kann ich mich selbst registrieren?

Nein. Das Onboarding wird vom Betreiber angestoßen. Wenden Sie sich an den Registerbetreiber, um ein Onboarding zu beantragen. So ist sichergestellt, dass alle Beteiligten vor dem Zugang zur Plattform verifiziert sind.

---

## Emittenten

### Wie lange dauert das Genehmigungsverfahren?

Die Prüfdauer ist betreiber- und einzelfallabhängig. Dieses Repository definiert oder garantiert kein Servicelevel von 1–3 Werktagen; fragen Sie den verantwortlichen Betreiber nach dem geltenden Verfahren und den Fristen.

### Kann ich die Token-Parameter nach der Genehmigung ändern?

Nein. Sobald eine Emission im Zustand APPROVED ist, sind alle Parameter (Name, ISIN, Chain, Token-Standard, Gesamtvolumen) fixiert. Sie können die Einreichung zurückziehen und nach DRAFT zurückkehren, um Änderungen vorzunehmen.

### Was bedeutet „onchain level"?

Er bestimmt, wie viel Ihrer Compliance-Logik auf der Blockchain liegt:
- **None** — nur Registeraufzeichnung, kein Smart Contract ausgebracht
- **Simple** — Standard-Token-Contract ausgebracht, keine Compliance-Durchsetzung
- **Control** — ERC-3643-Contract mit On-Chain-Compliance-Modulen ausgebracht

### Kann ich auf mehreren Chains ausbringen?

Derzeit wird jede Emission in genau ein Netz ausgebracht. Um dasselbe Wertpapier auf mehreren Chains zu emittieren, würden Sie getrennte Emissionen mit derselben ISIN anlegen. Wenden Sie sich an den Registerbetreiber, wenn Sie Multi-Chain-Unterstützung benötigen.

### Was geschieht mit meinem Token, wenn das Register offline geht?

Ist ein Token einmal ausgebracht, kann der Contract unabhängig von dieser Anwendung fortbestehen — je nach gewähltem Netz und den Steuerungsmöglichkeiten des Contracts. Registerwerk führt eine operative Inhaberaufzeichnung und projiziert bzw. gleicht ausgewählten Zustand on-chain ab. Welche Aufzeichnung rechtlich maßgeblich ist, hängt vom Instrument, vom Registermodell und von der Jurisdiktion ab und erfordert eine genehmigte Perimeterentscheidung; ein indizierter oder On-Chain-Saldo allein ist kein Nachweis rechtlicher Inhaberschaft oder rechtlicher Wirkung.

---

## Anleger

### Brauche ich eine besondere Wallet, um Security-Token zu halten?

Für ERC-20-Token funktioniert jede gängige EVM-Wallet (MetaMask, Ledger usw.). Für ERC-3643-Token funktioniert ebenfalls jede EVM-Wallet, die ERC-20 unterstützt — die Compliance-Logik steckt im Contract, nicht in der Wallet. Für vertrauliche ERC-3643-Token brauchen Sie eine FHE-fähige Wallet im Fhenix- oder Inco-Netz.

### Warum kann ich an meiner Wallet-Adresse keine Token empfangen?

Die häufigsten Gründe sind:
1. Ihre Wallet wurde vom Emittenten nicht zugelassen
2. Ihre KYC/AML-Claims sind abgelaufen — prüfen Sie **Profile → Identity**
3. Ihr Land ist durch ein Compliance-Modul dieses Tokens beschränkt
4. Der Token ist derzeit ausgesetzt

### Wie werde ich im KYC genehmigt?

Der Registerbetreiber steuert den KYC-Prozess. Sie werden während des Onboardings durch die Einreichung der Dokumente geführt. Ist Ihr KYC offen oder abgelaufen, gehen Sie zu **Profile → Identity → Renew KYC**.

### Sind meine Token-Bestände öffentlich?

Bei gewöhnlichen ERC-20-, ERC-721-, ERC-1155- und ERC-3643-Token: ja, Ihr Saldo ist auf der öffentlichen Blockchain für jeden sichtbar, der Ihre Wallet-Adresse kennt. Bei vertraulichen ERC-3643-Token: nein, Ihr Saldo ist on-chain verschlüsselt.

---

## Prüfer

### Können Prüfer Transaktionen auslösen?

Nein. Die Prüferrolle ist strikt lesend. Keine Prüferhandlung kann eine Registeraufzeichnung ändern oder eine On-Chain-Transaktion auslösen.

### Wie überprüfe ich, dass die Registerdaten mit der Blockchain übereinstimmen?

Jede Übertragungsaufzeichnung im Register enthält den On-Chain-Transaktions-Hash. Mit diesem Hash können Sie jede Übertragung unabhängig im jeweiligen Block-Explorer nachprüfen. Einzelheiten im [Prüferleitfaden](workspaces/auditor.md).

### Kann ich Prüfdaten für eigene Systeme exportieren?

Ja. Das Audit-Log und die Ansichten zur Token-Historie unterstützen CSV- und JSON-Exporte. Bei großen Zeiträumen werden Exporte asynchron erzeugt und Ihnen per E-Mail zugesandt.

---

## Technisches

### Welche Blockchains werden unterstützt?

EVM-Chains (Ethereum, Polygon, Base), Solana, Canton, StarkNet, Stellar sowie vertrauliche EVM-Netze. Für Tests stehen zudem Testnetze zur Verfügung (Sepolia, Amoy, Base Sepolia, Solana Devnet). Die vollständige Liste und wofür sich das jeweilige Netz eignet, steht unter [Unterstützte Blockchains](../blockchains/index.md).

### Welche Token-Standards werden unterstützt?

ERC-20, ERC-721, ERC-1155, ERC-3525, ERC-3643, ERC-4626, ERC-7540, ihre vertraulichen Varianten, Solana SPL-2022 sowie Registerwerks eigene Daml-Bond-Lebenszyklusvorlagen auf Canton. Die generische `CANTON_TOKEN`-Bereitstellung ist reserviert, aber nicht implementiert. Orientierung bietet [Den Token-Standard wählen](./issuers/token-standards.md).

### Wie greife ich auf die API zu?

Die REST-API ist unter `https://api.registerwerk.example.com` erreichbar. Die Dokumentation liegt unter `/swagger-ui.html`. Zur Authentifizierung benötigen Sie ein JWT-Token Ihres Identity Providers. Siehe [Anmelden](./authentication.md).
