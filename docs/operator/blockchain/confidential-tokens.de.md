---
title: Vertrauliche Token (Zama fhEVM)
---

# Vertrauliche Token einrichten (Zama fhEVM)

Diese Anleitung behandelt die Bereitstellung und Verwaltung vertraulicher ERC-20-/ERC-3643-Token mit Zamas fhEVM.

## Voraussetzungen

1. Eine Chain mit **echter Zama-fhEVM-Infrastruktur** — heute Ethereum Sepolia (dokumentierte Adressen sind in `contracts/lib/fhevm/config/` vendored und in `@zama-fhe/relayer-sdk` als `SepoliaConfig` gebündelt) oder Ethereum-/Base-Mainnet, sobald Zama dort endgültige Adressen veröffentlicht. Die vertrauliche Bereitstellung ist auf `Chain.ETHEREUM`/`Chain.BASE` beschränkt — **nicht** Fhenix/Inco.
2. `EwpgConfidentialFactory` bereitgestellt und mit den echten FHEVM-Adressen dieser Chain konfiguriert (`setFhevmInfra`) — siehe `docs/blockchains/confidential-evm.md` im Repository.
3. Nur für `CONF_ERC3643`: eine echte T-REX-`IdentityRegistry`, die für vertrauliche Assets auf dieser Chain bereitgestellt und über `registerwerk.contracts.confidential-identity-registry.<chain>` konfiguriert ist. Die Bereitstellung schlägt sichtbar fehl, wenn dies nicht gesetzt ist.
4. Die dedizierten Nur-Entschlüsselung-Viewer-Adressen des Betreibers und eines Prüfers, konfiguriert über `registerwerk.contracts.confidential-operator-viewer.<chain>` / `.confidential-auditor-viewer.<chain>` — diese werden ab Block eins zu Viewern auf jedem auf dieser Chain bereitgestellten vertraulichen Token.
5. `zama-relayer` läuft (`docker compose --profile confidential up`), wobei `OPERATOR_DECRYPT_PRIVATE_KEY` auf den privaten Schlüssel gesetzt ist, der zur obigen Operator-Viewer-Adresse passt, und `registerwerk.zama.relayer-url` des Backends darauf zeigt.

## Bereitstellen

Standard-Asset-Bereitstellungsablauf, wie bei jedem anderen Standard:

```bash
curl -X POST http://localhost:48080/api/v1/assets/{assetId}/deploy \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  -d '{ "chain": "ETHEREUM", "network": "TESTNET" }'
```

Das Backend leitet `CONF_ERC20`/`CONF_ERC3643` an `ConfidentialErc20Service`/`ConfidentialErc3643Service` weiter, die `EwpgConfidentialFactory.deployConfidentialErc20`/`deployConfidentialErc3643` aufrufen — echte Web3j-Transaktionen, die die konfigurierten Operator-/Auditor-Viewer-Adressen als `initialViewers` übergeben.

## Heute verfügbare Betreiberaktionen

| Aktion | Endpunkt | Hinweise |
|---|---|---|
| Vertrauliches Minting (Emittenten-/Betreiberausgabe) | `POST /api/v1/assets/{id}/deployments/{depId}/issuer/mint-confidential` | Verschlüsselt den Betrag serverseitig über den `zama-relayer`-Sidecar — kein Browser/Wallet nötig |
| Vertrauliche Zwangsvernichtung (Forced Burn, §26 Einziehung) | `POST .../admin/force-burn-confidential` | Derselbe serverseitige Verschlüsselungspfad; bereits Agent-/Owner-gated — dieses Gating IST die Zwangsvernichtungsbefugnis |
| Vertraulichen Viewer hinzufügen | `POST .../admin/confidential-add-viewer` | Gewährt ab sofort Entschlüsselungsrechte für den Saldo jedes Inhabers — z. B. einen Prüfer oder die eigene Wallet des Emittenten nach der Bereitstellung hinzufügen |
| Vertraulichen Viewer entfernen | `POST .../admin/confidential-remove-viewer` | Stoppt künftige Gewährungen — widerruft NICHT rückwirkend bereits entschlüsselbare historische Handles (Zamas ACL hat keine Widerruf-Primitive) |
| Abgleich Register vs. On-Chain | `GET /api/v1/assets/{id}/confidential-reconciliation` | Headless: entschlüsselt den On-Chain-Saldo jedes Inhabers über den eigenen Operator-Decrypt-Key des Backends und vergleicht ihn mit dem Klartext-`nominalAmount` des Registers. Rolle `REGISTRY_ADMIN` oder `AUDIT`. |
| Über die eigene Wallet aufdecken und abgleichen | Betreiberportal → Asset → Registerkarte **Confidential Balances** (Vertrauliche Salden) | Verbinden Sie eine Viewer-Wallet im Browser und entschlüsseln Sie direkt über Zamas Relayer — eine unabhängige Gegenprüfung des obigen Headless-Abgleichs |
| Öffentliche/Orakel-Offenlegung des Bestands | `ConfidentialERC20.requestSupplyDisclosure()` (On-Chain-Aufruf; noch kein Betreiber-API-Endpunkt kapselt ihn) | Für eine von der Aufsichtsbehörde ausgelöste aggregierte Offenlegung, nicht für den Saldo eines bestimmten Inhabers |

Freeze/Pause/Zwangsübertragung für `CONF_ERC3643` sind **noch nicht** über die Betreiber-API angebunden — der bestehende ERC-3643-Admin-Controller zielt auf die ABI des Klartext-Contracts `EwpgERC3643`, die nicht zu den verschlüsselten-Betrag-Signaturen von `ConfidentialERC3643` passt.

## Der Relayer-Sidecar

`zama-relayer` (Repo-Root `zama-relayer/`) ist Registerwerks eigener Dienst, der den echten `@zama-fhe/relayer-sdk` verpackt — in diesem Monorepo erstellt und ausgeliefert, nichts, das Sie selbst schreiben müssen. Zama veröffentlicht keinen Java-/JVM-Client, was der einzige Grund ist, warum dieser Sidecar existiert; jede vom Browser initiierte vertrauliche Aktion (Anleger/Emittent/Prüfer deckt einen Saldo auf, vertrauliche Überweisung eines Anlegers) spricht direkt vom Browser aus mit Zamas Relayer und berührt diesen Sidecar nie. Aktivieren Sie ihn mit:

```bash
docker compose --profile confidential up
```

Die Umgebungsvariablen finden Sie im Abschnitt „Confidential tokens (Zama fhEVM)" der `.env.example` — `ZAMA_CONFIG_PRESET=sepolia`, `ZAMA_OPERATOR_DECRYPT_PRIVATE_KEY` und `REGISTERWERK_ZAMA_RELAYER_URL` auf der Backend-Seite.

## Saldo-Entschlüsselung für Anleger/Emittent/Prüfer

Das Aufdecken eines vertraulichen Saldos (oder das Verschlüsseln eines vertraulichen Übertragungsbetrags) ist in beiden Frontends ein **clientseitiger** Vorgang: Die verbundene Wallet signiert eine EIP-712-Anfrage, und die eigene `@zama-fhe/relayer-sdk`-Instanz des Browsers spricht direkt mit Zamas Relayer — siehe `FheClientService` in `frontend-customer` (Selbstaufdeckung des Anlegers + vertrauliche Übertragung; Aufdeckung aller Inhaber durch den Emittenten) und `frontend-operator` (`ConfidentialViewerPanelComponent` für Betreiber/Prüfer). Nichts davon läuft über dieses Backend.
