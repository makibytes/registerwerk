---
title: ERC-20 – Fungibler Token
description: ERC-20 Standardimplementierung für Equity-, Utility- und einfache fungible Sicherheitstoken.
---

# ERC-20 – Fungibles Token { #erc-20-fungible-token }

ERC-20 ist der grundlegende fungible Token-Standard für EVM-Chains. Jede Einheit ist identisch und
austauschbar. Registerwerk setzt ERC-20-Token für Eigenkapitalinstrumente, einfache Schuldtitel
und Utility-Token ein, bei denen KYC-Gating auf Vertragsebene nicht erforderlich ist (die
Compliance wird stattdessen auf der Registerebene durchgesetzt).

---

## Wann ERC-20 verwenden { #when-to-use-erc-20 }

- **Aktientoken** – Anteile an einem nicht börsennotierten Unternehmen, bei denen
  Übertragungsbeschränkungen off-chain verwaltet werden
- **Einfache Anleihen** – wenn der Emittent keine On-Chain-Durchsetzung von
  Übertragungsbeschränkungen benötigt
- **Utility-Token** – für plattforminterne Guthaben oder Incentive-Token
- **Testemissionen** – ERC-20 ist der einfachste Bereitstellungspfad für neue Emittenten, die sich
  mit der Plattform vertraut machen

Für regulierte Wertpapiere, die On-Chain-KYC-Gating erfordern, ziehen Sie [ERC-3643](erc3643.md)
in Betracht. Für Anleihen mit mehreren Tranchen ziehen Sie [ERC-3525](erc3525.md) in Betracht.

---

## Registerwerk-ERC-20-Erweiterungen { #registerwerk-erc-20-extensions }

Registerwerk stellt einen eigenen `EwpgERC20`-Vertrag bereit, der den Standard-ERC-20 erweitert um:

| Erweiterung | Zweck |
|---|---|
| `mintWithCap` | Respektiert die vom Betreiber konfigurierte `MintControlRule.maxSupply` |
| `pause` / `unpause` | Notfallschalter für den Registerbetreiber |
| `freeze(address)` | Freeze auf Registerebene (zugeordnet zu `HolderBlock` in der Datenbank) |
| `setIsin(string)` | Speichert die ISIN on-chain für Querverweise |
| `setRegistryRef(string)` | Speichert die Registerwerk-Asset-ID für Prüfzwecke |

---

## Bereitstellungsablauf { #deployment-flow }

1. Der Betreiber wählt `TokenStandard.ERC20` beim Anlegen eines `Asset`
2. Nach KYC-Genehmigung und (optional) Step-up-Authentifizierung ruft er `POST /api/v1/assets/{id}/deploy` auf
3. `Erc20DeploymentService` erstellt und sendet die Bereitstellungstransaktion
4. Nach Empfangsbestätigung wird `AssetDeployment` mit `contractAddress` und `deploymentTxHash` angelegt
5. `Asset.status` wechselt zu `ISSUED`

---

## On-Chain-Administratoroperationen { #on-chain-admin-operations }

| Operation | Endpunkt | Erfordert |
|---|---|---|
| Token minten | `POST /api/v1/assets/{id}/mint` | REGISTRY_ADMIN + Step-up (wenn die Obergrenze des Bestands verwaltet wird) |
| Token vernichten | `POST /api/v1/assets/{id}/burn` | REGISTRY_ADMIN + Step-up + Vier-Augen |
| Zwangsübertragung | `POST /api/v1/assets/{id}/force-transfer` | REGISTRY_ADMIN + Step-up + Vier-Augen |
| Adresse einfrieren | `POST /api/v1/assets/{id}/freeze/{address}` | REGISTRY_ADMIN + aktiver HolderBlock |
| Vertrag pausieren | `POST /api/v1/assets/{id}/pause` | REGISTRY_ADMIN + Step-up |

---

## Vertrauliche Variante { #confidential-variant }

`CONF_ERC20` stellt eine [Zama-fhEVM](confidential.md)-vertrauliche Variante auf den Fhenix- oder
Inco-Netzwerken bereit, bei der Guthaben und Transferbeträge mittels vollständig homomorpher
Verschlüsselung verschlüsselt werden. Verwenden Sie diese, wenn der Emittent Vertraulichkeit der
Anlegerpositionen benötigt.
