---
title: Fehlerbehebung
---

# Fehlerbehebung

Diese Seite behandelt die häufigsten Probleme beim Betrieb des eWpG-Registers, zusammen mit ihren Grundursachen und Lösungen.

## Blockchain-/RPC-Fehler

### „Blockchain RPC call failed" in Backend-Protokollen

**Symptom**: Backend-Protokolle zeigen `BlockchainException: RPC call failed for chain mainnet`, und API-Aufrufe liefern HTTP 502.

**Ursache**: Der konfigurierte RPC-Endpunkt ist nicht erreichbar oder liefert Fehler zurück.

**Lösung**:

1. Testen Sie den RPC-Endpunkt manuell:

   ```bash
   curl -X POST $ETH_MAINNET_RPC \
     -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
   ```

2. Liefert er einen Fehler, wechseln Sie in `.env` zu einem Backup-RPC und starten Sie das Backend neu
3. Prüfen Sie die Statusseite Ihres RPC-Anbieters auf laufende Vorfälle
4. Erwägen Sie, in der Chain-Konfiguration eine Fallback-RPC-URL zu hinterlegen

### Token-Bereitstellungstransaktion wird nie bestätigt

**Symptom**: Nach Klick auf „Deploy to Blockchain" bleibt der Status unbegrenzt bei „Deploying".

**Ursache**: Die Bereitstellungstransaktion wurde übermittelt, aber nie bestätigt (z. B. Gaspreis zu niedrig, Netzwerküberlastung).

**Lösung**:

1. Notieren Sie den Transaktions-Hash von der Emissionsdetailseite
2. Schlagen Sie ihn im Block-Explorer nach — ist er ausstehend oder verworfen?
3. Bei ausstehend: warten Sie, bis sich die Netzwerküberlastung legt, oder beschleunigen Sie mit `cast`:

   ```bash
   cast send --gas-price 150gwei <tx-hash> --rpc-url $RPC_URL --private-key $DEPLOYER_KEY
   ```

4. Bei verworfen: Das Backend wiederholt automatisch alle 5 Minuten (bis zu 3-mal)
5. Schlagen alle Wiederholungen fehl, kehrt die Emission in den Status APPROVED zurück — klicken Sie erneut auf Deploy

---

## Indexer-Lücken

### Subgraph synchronisiert nicht

**Symptom**: Das Dashboard zeigt die Chain als „DEGRADED" oder „CRITICAL". Graph-Node-Protokolle zeigen, dass die Indizierung ins Stocken geraten ist.

**Lösung**:

1. Prüfen Sie die Graph-Node-Protokolle:

   ```bash
   docker compose logs --tail=50 graph-node | grep -i "error\|failed\|panic"
   ```

2. Prüfen Sie den RPC-Status — häufig verursacht dadurch, dass der RPC-Anbieter den Graph-Node ratenbegrenzt
3. Hinterlegen Sie in `graph-node.toml` einen zweiten RPC-Anbieter als Fallback
4. Starten Sie graph-node neu:

   ```bash
   docker compose restart graph-node
   ```

5. Hängt der Subgraph mit einem fatalen Fehler fest, stellen Sie ihn neu bereit (siehe [The Graph](./indexers/the-graph.md))

### Fehlende Übertragungsereignisse im Register

**Symptom**: Eine im Block-Explorer sichtbare Übertragung erscheint nicht im Register.

**Ursache**: Der Indexer lag zum Zeitpunkt der Übertragung hinter dem Chain-Head zurück, oder der Subgraph wurde nach der Übertragung ab einem Startblock neu bereitgestellt.

**Lösung**:

1. Prüfen Sie den aktuellen Indexer-Status:

   ```bash
   curl http://localhost:8080/api/v1/admin/chains \
     -H "Authorization: Bearer $OPERATOR_JWT" \
     | jq '.[].latestIndexedBlock'
   ```

2. Hat der Indexer aufgeholt und das Ereignis fehlt weiterhin, führen Sie einen unabhängig kontrollierten Vergleich der Subgraph-Ereignisse gegen `eth_getLogs` für den betroffenen Bereich durch. Der geplante Admin-Endpunkt `verify-consistency` ist nicht implementiert.

3. Ist eine Lücke bestätigt, stellen Sie den Subgraph ab einem Block vor dem fehlenden Ereignis neu bereit

---

## KYC-Upload-Fehler

### Fehler „Document too large"

**Symptom**: Der KYC-Dokumenten-Upload schlägt mit „file size exceeds limit" fehl.

**Ursache**: Das Standard-Größenlimit für Dokumente beträgt 20 MB (Kongs `request-size-limiting`-Plugin).

**Lösung**: Komprimieren Sie das Dokument vor dem Upload. Überschreitet das Originaldokument 20 MB, bitten Sie den Kunden um eine komprimierte Fassung. Als Operator können Sie das Limit in `gateway/kong.yml` erhöhen:

```yaml
plugins:
  - name: request-size-limiting
    config:
      allowed_payload_size: 50  # MB
```

### „S3 upload failed" in Backend-Protokollen

**Symptom**: Backend-Protokolle zeigen `S3UploadException`, wenn ein KYC-Dokument gespeichert wird.

**Ursache**: Die S3-Zugangsdaten sind falsch, der Bucket existiert nicht, oder die IAM-Policy erlaubt kein `PutObject`.

**Lösung**:

1. Prüfen Sie die S3-Zugangsdaten in `.env`
2. Testen Sie den S3-Zugriff:

   ```bash
   aws s3 ls s3://your-kyc-bucket
   ```

3. Stellen Sie sicher, dass die IAM-Policy für den Bucket `s3:PutObject`, `s3:GetObject` und `s3:DeleteObject` enthält

---

## Authentifizierungsfehler

### „JWT validation failed" — 401-Antworten

**Symptom**: API-Anfragen liefern 401 mit `JWT validation failed`, obwohl der Token gültig aussieht.

**Ursache**: Der Token-Aussteller stimmt nicht mit `JWT_ISSUER_URI` überein, oder der JWKS-Endpunkt ist nicht erreichbar.

**Lösung**:

1. Dekodieren Sie das JWT unter [jwt.io](https://jwt.io) und prüfen Sie, ob der `iss`-Claim mit Ihrem `JWT_ISSUER_URI` übereinstimmt
2. Prüfen Sie, ob das Backend den JWKS-Endpunkt erreichen kann:

   ```bash
   docker exec registerwerk-backend-1 \
     curl ${JWT_ISSUER_URI}/.well-known/jwks.json
   ```

3. Ist der JWKS-Endpunkt aus dem Docker-Netzwerk heraus nicht erreichbar, setzen Sie `JWT_JWKS_URI` explizit

### Benutzer können sich nach IdP-Wechsel nicht anmelden

**Symptom**: Nach dem Umstellen einer Kundenentität auf einen benutzerdefinierten IdP erhalten deren Benutzer „Access denied".

**Lösung**:

1. Prüfen Sie, ob die Redirect-URI des Kunden-IdP korrekt gesetzt ist
2. Testen Sie die IdP-Integration über **Entities → [Entität] → Identity Provider → Test**
3. Prüfen Sie die Backend-Protokolle auf den konkreten OIDC-Fehler (meist `redirect_uri_mismatch` oder `invalid_client`)

---

## Datenbankprobleme

### Backend startet nicht — Flyway-Migrationsfehler

**Symptom**: Der Backend-Container beendet sich beim Start mit `FlywayException: Validate failed`.

**Ursache**: Eine Migrationsdatei wurde nach ihrer Anwendung verändert, oder Migrationen liegen in falscher Reihenfolge vor.

**Lösung**:

```bash
# Check current migration state
docker exec registerwerk-postgres-1 \
  psql -U ewpg -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"
```

Zeigt eine Migration `success = false`, korrigieren Sie das Migrations-SQL und starten Sie neu. Ändern Sie niemals Migrationsdateien, die bereits auf die Produktion angewendet wurden.

### Festplatte auf PostgreSQL-Volume voll

**Symptom**: Das Backend liefert 500er-Fehler. Postgres-Protokolle zeigen `FATAL: could not write to file`.

**Lösung**:

1. Identifizieren Sie die größten Tabellen:

   ```sql
   SELECT relname, pg_size_pretty(pg_total_relation_size(relid))
   FROM pg_catalog.pg_statio_user_tables
   ORDER BY pg_total_relation_size(relid) DESC LIMIT 10;
   ```

2. Die Tabelle `audit_log` ist monatsweise partitioniert (Range-Partitionierung). Löschen Sie bei kritischem Plattenplatz alte Partitionen:

   ```sql
   DROP TABLE audit_log_y2024m01;
   ```

3. Erweitern Sie das Docker-Volume und starten Sie PostgreSQL neu

# Fehlerbehebung

## Backend startet nicht

**Symptom**: `Connection refused` auf `localhost:8080`

1. DB-Konnektivität prüfen: `docker compose logs postgres`
2. Flyway-Migrationen prüfen: nach `FlywayException` in den Backend-Protokollen suchen
3. Prüfen, ob erforderliche Umgebungsvariablen gesetzt sind (insbesondere `DB_PASSWORD`, `JWT_ISSUER_URI`)

## Token erscheinen nicht im Verlauf

**Symptom**: `GET /api/v1/assets/{id}/history` liefert leer zurück

1. Prüfen, ob graph-node läuft: `curl http://localhost:8020/health`
2. Prüfen, ob der Subgraph bereitgestellt ist: `curl http://localhost:8000/subgraphs/name/ewpg/ethereum-sepolia`
3. Indexer-Status prüfen: `SELECT * FROM indexer_state;`
4. Prüfen, ob `graphNodeUrl` und `graphSubgraphName` in der Chain-Konfiguration gesetzt sind

## ERC-3643-Bereitstellung schlägt fehl

**Symptom**: `POST /api/v1/assets/{id}/deployments` liefert 500

1. Prüfen, ob die Deployer-Wallet über ETH für Gas verfügt
2. Prüfen, ob `REGISTRY_WALLET_PRIVATE_KEY` gesetzt ist
3. Prüfen, ob das T-REX-Submodul initialisiert ist: `ls contracts/lib/erc3643/`
4. Nach einem `Web3j`-Fehler in den Backend-Protokollen suchen

## Kong liefert 401 Unauthorized

Kong validiert keine JWTs — ein 401 kommt immer vom **Backend**, auch bei Anfragen, die
durch Kong laufen. Prüfen Sie der Reihe nach:

1. Ob `JWT_ISSUER_URI` mit dem `iss` übereinstimmt, den Ihr OIDC-Anbieter tatsächlich zurückgibt
2. Ob `JWT_AUDIENCE` mit dem `aud` des Tokens übereinstimmt — eine Abweichung hier ist die häufigste Ursache
3. Bei einem Operator-Token ist `iss` gleich `registerwerk-local`; lokale Token ohne dieses Feld
   werden by design abgelehnt, sodass ein handgefertigter Token ohne `iss` immer 401 liefert
4. Den Token dekodieren, um seine Claims zu prüfen

Ist der Token gültig und Sie erhalten **403**, ist der Token in Ordnung und die *Rolle* nicht —
ein völlig anderes Problem. Siehe [Rollen und Berechtigungen](customers/roles.md).

## Onboarding-Token abgelaufen

Über die API neu erzeugen:
```bash
curl -X POST http://localhost:8000/api/v1/onboarding/tokens \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -d '{"legalEntityId": "<uuid>", "recipientEmail": "admin@acme.de"}'
```

Alte Token werden automatisch invalidiert (partieller Unique-Index `WHERE used_at IS NULL`).

## Übertragung vertraulicher Token schlägt auf Fhenix fehl

1. Sicherstellen, dass der Client `fhevmjs` zur Verschlüsselung des Betrags verwendet
2. Sicherstellen, dass das ONCHAINID des Anlegers über einen gültigen KYC-Claim verfügt
3. Prüfen, ob die Anlegeradresse im IdentityRegistry des Tokens auf der Whitelist steht
4. Das Fhenix-Testnetz kann Faucet-limitierte Konten haben — Gas-Guthaben prüfen
