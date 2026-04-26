---
id: troubleshooting
title: Troubleshooting
sidebar_label: Troubleshooting
sidebar_position: 10
---

# Troubleshooting

This page covers the most common issues encountered when operating the eWpG Registry, along with their root causes and solutions.

## Blockchain / RPC errors

### "Blockchain RPC call failed" in backend logs

**Symptom**: Backend logs show `BlockchainException: RPC call failed for chain mainnet` and API calls return HTTP 502.

**Cause**: The configured RPC endpoint is unreachable or returning errors.

**Solution**:

1. Test the RPC endpoint manually:

   ```bash
   curl -X POST $ETH_MAINNET_RPC \
     -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
   ```

2. If it returns an error, switch to a backup RPC in `.env` and restart the backend
3. Check your RPC provider's status page for ongoing incidents
4. Consider adding a fallback RPC URL in the chain config

### Token deployment transaction never confirms

**Symptom**: After clicking "Deploy to Blockchain", the status stays at "Deploying" indefinitely.

**Cause**: The deployment transaction was submitted but never confirmed (e.g., gas price too low, network congestion).

**Solution**:

1. Note the transaction hash from the issuance detail page
2. Look it up on the block explorer — is it pending or dropped?
3. If pending, wait for network congestion to clear, or use `cast` to speed up:

   ```bash
   cast send --gas-price 150gwei <tx-hash> --rpc-url $RPC_URL --private-key $DEPLOYER_KEY
   ```

4. If dropped, the backend will retry automatically every 5 minutes (up to 3 times)
5. If all retries fail, the issuance returns to APPROVED status — click Deploy again

---

## Indexer gaps

### Subgraph is not syncing

**Symptom**: Dashboard shows chain as "DEGRADED" or "CRITICAL". Graph Node logs show indexing has stalled.

**Solution**:

1. Check Graph Node logs:

   ```bash
   docker compose logs --tail=50 graph-node | grep -i "error\|failed\|panic"
   ```

2. Check the RPC status — often caused by the RPC provider rate-limiting the graph-node
3. Add a second RPC provider as fallback in `graph-node.toml`
4. Restart graph-node:

   ```bash
   docker compose restart graph-node
   ```

5. If the subgraph is stuck with a fatal error, re-deploy it (see [The Graph](./indexers/the-graph))

### Missing transfer events in registry

**Symptom**: A transfer visible on the block explorer does not appear in the registry.

**Cause**: Indexer was behind the chain head at the time of the transfer, or the subgraph was re-deployed from a start block after the transfer.

**Solution**:

1. Check the current indexer state:

   ```bash
   curl http://localhost:8080/api/v1/admin/chains \
     -H "Authorization: Bearer $OPERATOR_JWT" \
     | jq '.[].latestIndexedBlock'
   ```

2. If the indexer has caught up and the event is still missing, run the consistency checker:

   ```bash
   curl -X POST http://localhost:8080/api/v1/admin/verify-consistency \
     -H "Authorization: Bearer $OPERATOR_JWT" \
     -d '{"chainId": 1, "fromBlock": X, "toBlock": Y}'
   ```

3. If a gap is confirmed, re-deploy the subgraph from a block before the missing event

---

## KYC upload errors

### "Document too large" error

**Symptom**: KYC document upload fails with "file size exceeds limit".

**Cause**: The default document size limit is 20 MB (Kong's `request-size-limiting` plugin).

**Solution**: Compress the document before uploading. If the original document exceeds 20 MB, ask the customer to provide a compressed version. As operator, you can increase the limit in `gateway/kong.yml`:

```yaml
plugins:
  - name: request-size-limiting
    config:
      allowed_payload_size: 50  # MB
```

### "S3 upload failed" in backend logs

**Symptom**: Backend logs show `S3UploadException` when a KYC document is saved.

**Cause**: S3 credentials are incorrect, the bucket does not exist, or the IAM policy does not allow `PutObject`.

**Solution**:

1. Verify S3 credentials in `.env`
2. Test S3 access:

   ```bash
   aws s3 ls s3://your-kyc-bucket
   ```

3. Ensure the IAM policy includes `s3:PutObject`, `s3:GetObject`, and `s3:DeleteObject` for the bucket

---

## Authentication errors

### "JWT validation failed" — 401 responses

**Symptom**: API requests return 401 with `JWT validation failed` even though the token looks valid.

**Cause**: Token issuer does not match `JWT_ISSUER_URI`, or the JWKS endpoint is unreachable.

**Solution**:

1. Decode the JWT at [jwt.io](https://jwt.io) and verify the `iss` claim matches your `JWT_ISSUER_URI`
2. Check the backend can reach the JWKS endpoint:

   ```bash
   docker exec registerwerk-backend-1 \
     curl ${JWT_ISSUER_URI}/.well-known/jwks.json
   ```

3. If the JWKS endpoint is unreachable from inside the Docker network, set `JWT_JWKS_URI` explicitly

### Users cannot log in after IdP change

**Symptom**: After switching a customer entity to a custom IdP, their users get "Access denied".

**Solution**:

1. Verify the customer IdP's redirect URI is set correctly
2. Test the IdP integration via **Entities → [entity] → Identity Provider → Test**
3. Check backend logs for the specific OIDC error (usually `redirect_uri_mismatch` or `invalid_client`)

---

## Database issues

### Backend fails to start — Flyway migration error

**Symptom**: Backend container exits on startup with `FlywayException: Validate failed`.

**Cause**: A migration file was modified after it was applied, or migrations are out of order.

**Solution**:

```bash
# Check current migration state
docker exec registerwerk-postgres-1 \
  psql -U ewpg -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"
```

If a migration shows `success = false`, fix the migration SQL and restart. Never modify migration files that have already been applied to production.

### Disk full on PostgreSQL volume

**Symptom**: Backend returns 500 errors. Postgres logs show `FATAL: could not write to file`.

**Solution**:

1. Identify the largest tables:

   ```sql
   SELECT relname, pg_size_pretty(pg_total_relation_size(relid))
   FROM pg_catalog.pg_statio_user_tables
   ORDER BY pg_total_relation_size(relid) DESC LIMIT 10;
   ```

2. The `audit_log` table is range-partitioned by month. Drop old partitions if disk is critical:

   ```sql
   DROP TABLE audit_log_y2024m01;
   ```

3. Expand the Docker volume and restart PostgreSQL

# Troubleshooting

## Backend won't start

**Symptom**: `Connection refused` on `localhost:8080`

1. Check DB connectivity: `docker compose logs postgres`
2. Check Flyway migrations: look for `FlywayException` in backend logs
3. Verify required env vars are set (especially `DB_PASSWORD`, `JWT_ISSUER_URI`)

## Tokens not appearing in history

**Symptom**: `GET /api/v1/assets/{id}/history` returns empty

1. Check graph-node is running: `curl http://localhost:8020/health`
2. Check subgraph is deployed: `curl http://localhost:8000/subgraphs/name/ewpg/ethereum-sepolia`
3. Check indexer state: `SELECT * FROM indexer_state;`
4. Verify `graphNodeUrl` and `graphSubgraphName` are set on the chain config

## ERC-3643 deployment fails

**Symptom**: `POST /api/v1/assets/{id}/deployments` returns 500

1. Check deployer wallet has ETH for gas
2. Verify `REGISTRY_WALLET_PRIVATE_KEY` is set
3. Check the T-REX submodule is initialized: `ls contracts/lib/erc3643/`
4. Look for `Web3j` error in backend logs

## Kong returns 401 Unauthorized

1. Verify `JWT_ISSUER_URI` matches what your OIDC provider returns in the `iss` claim
2. Verify Kong OIDC plugin values (`ENTRA_ISSUER`, `ENTRA_CLIENT_ID`, `ENTRA_CLIENT_SECRET`) are correct
3. Decode the JWT at [jwt.io](https://jwt.io) to inspect claims

## Onboarding token expired

Re-generate via API:
```bash
curl -X POST http://localhost:8000/api/v1/onboarding/tokens \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -d '{"legalEntityId": "<uuid>", "recipientEmail": "admin@acme.de"}'
```

Old tokens are invalidated automatically (partial unique index `WHERE used_at IS NULL`).

## Confidential token transfer fails on Fhenix

1. Ensure the client is using `fhevmjs` to encrypt the amount
2. Verify the investor's ONCHAINID has a valid KYC claim
3. Check the investor address is whitelisted in the token's IdentityRegistry
4. Fhenix testnet may have faucet-limited accounts — verify gas balance
