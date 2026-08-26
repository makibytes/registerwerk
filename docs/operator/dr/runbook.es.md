---
title: Manual de recuperación ante desastres
description: Borrador de manual operativo para la restauración de Postgres y del backend, la verificación de la cadena de auditoría y la clasificación de incidentes DORA — pendiente de aprobación y pruebas por parte del operador.
---

# Manual de recuperación ante desastres { #disaster-recovery-runbook }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Esto es un borrador de manual operativo, no evidencia de un plan de continuidad aprobado, de un RTO/RPO
    probado, de una clasificación de incidentes jurídicamente correcta ni de una notificación a las autoridades.
    El operador debe aprobarlo, ponerlo a prueba y conciliarlo con los requisitos legales, regulatorios,
    contractuales y de infraestructura vigentes.

**Servicio:** Registro eWpG Registerwerk  
**Objetivo de RTO:** ≤4 horas (eWpRV §6)  
**Objetivo de RPO:** ≤15 minutos (archivado de WAL mediante wal-g)  
**Responsable:** Equipo de Operaciones del Registro  
**Clasificación DORA:** incidente MAJOR si el tiempo de inactividad supera las 4 h

---

## 1. Clasificación de gravedad del incidente (DORA, art. 17) { #1-incident-severity-classification-dora-art-17 }

| Gravedad | Criterios | Acción | Plazo |
|---|---|---|---|
| MINOR | Un solo servicio caído, &lt;30 min, sin pérdida de datos | Alerta interna | — |
| MAJOR | Multiservicio, 30 min–4 h, posible impacto en los datos | Informe inicial a BaFin/CSSF/AMF/FMA | 72 h desde la detección |
| CRITICAL | Interrupción total >4 h O vulneración de la integridad de los datos | Informe inicial a la autoridad competente | 4 h desde la detección |

`POST /api/v1/dora/incidents` registra un incidente interno; no presenta un informe DORA. Toda
autoridad, plazo, formulario y canal indicados a continuación es un dato de referencia que debe
verificarse externamente:
- DE: BaFin (bafin.de), Referat IT-Risikoaufsicht
- LU: CSSF, a través del portal CSS
- FR: AMF / ACPR, a través de ONEGATE
- LI: FMA, a través del portal LIMA

---

## 2. Restauración completa de Postgres (RPO ≤15 min) { #2-postgres-full-restore-rpo-15-min }

### 2a. Restaurar desde la copia de seguridad de wal-g (ruta principal) { #2a-restore-from-wal-g-backup-primary-path }
```bash
# 1. Provision a new Postgres 18.6 instance
docker run -d --name postgres-restore postgres:18.6-alpine

# 2. Restore base backup
docker exec postgres-restore wal-g backup-fetch /var/lib/postgresql LATEST \
  --walg-s3-prefix s3://registerwerk-backups/wal-g

# 3. Replay WAL to target time
cat > /tmp/recovery.conf << EOF
restore_command = 'wal-g wal-fetch "%f" "%p"'
recovery_target_time = '$(date -u -d "-15 minutes" +"%Y-%m-%d %H:%M:%S")'
recovery_target_action = 'promote'
EOF

# 4. Start Postgres and wait for recovery
docker start postgres-restore
docker logs -f postgres-restore | grep "recovery is complete"

# 5. Validate row counts
psql -h localhost -U registerwerk -c "SELECT count(*) FROM audit_event;"
psql -h localhost -U registerwerk -c "SELECT max(occurred_at) FROM audit_event;"
```

### 2b. Restaurar desde pg_dump (alternativa — RPO = último volcado) { #2b-restore-from-pgdump-fallback-rpo-last-dump }
```bash
pg_restore -h new-host -U registerwerk -d registerwerk \
  --clean --if-exists \
  /backups/registerwerk_$(date +%Y%m%d).dump
```

---

## 3. Restauración del backend { #3-backend-restore }
```bash
# Pull signed image (verify Cosign signature first)
cosign verify ghcr.io/makibytes/registerwerk/backend:VERSION

# Deploy with production environment
docker run -d \
  --env-file /etc/registerwerk/prod.env \
  -e REGISTERWERK_PRODUCTION_MODE=true \
  -p 127.0.0.1:48080:8080 \
  ghcr.io/makibytes/registerwerk/backend:VERSION

# Verify health
curl http://localhost:48080/actuator/health | jq .status
```

---

## 4. Verificación de la cadena de auditoría tras la restauración { #4-audit-chain-verification-after-restore }
```bash
# Trigger audit chain verification via actuator
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:48080/actuator/health/auditChainVerificationService | jq .

# If BROKEN: do NOT resume operations. Escalate to DORA CRITICAL incident.
# The hash chain violation must be investigated before the registry resumes.
```

---

## 5. Material clave de emergencia — break-glass (sustituye al endpoint exportRaw eliminado) { #5-key-material-break-glass-replaces-removed-exportraw-endpoint }

El acceso a la clave privada sin procesar requiere las tres condiciones siguientes:
1. Dos de cada tres fragmentos Shamir (en poder del CTO, el CFO y el asesor jurídico externo)
2. Resolución del consejo (aviso mínimo de 24 h al asesor jurídico regulatorio)
3. Registro de break-glass aprobado y auditado internamente. Cualquier notificación al regulador es
   específica del incidente, del operador y de la jurisdicción, y debe seguir el procedimiento
   aprobado externamente; este repositorio no la presenta ante ninguna autoridad.

Acceso de emergencia a KMS (AWS KMS):
```bash
aws kms decrypt \
  --ciphertext-blob fileb://wallet-wrapped-dek.bin \
  --key-id arn:aws:kms:eu-central-1:ACCT:key/KEY_ID \
  --output text --query Plaintext | base64 -d > dek.bin
```

---

## 6. Restauración de Kong / la puerta de enlace { #6-kong-gateway-restore }
```bash
deck gateway sync gateway/kong.yml \
  --kong-addr http://localhost:48001
```

---

## 7. Lista de verificación posterior a la recuperación { #7-post-recovery-checklist }
- [ ] Estado de Postgres: `pg_isready`
- [ ] Estado del backend: `/actuator/health` → UP
- [ ] Cadena de auditoría: `/actuator/health/auditChainVerificationService` → UP
- [ ] Liveness del indexador: `/actuator/health/indexerMonitor` → UP
- [ ] Deriva de cadena: confirmar que no hay filas `chain_drift_event` abiertas con severity=CRITICAL
- [ ] Filtrado de sanciones: confirmar que no hay filas `screening_hit` abiertas con más de 4 h de antigüedad
- [ ] Resumen del registro: verificar que los importes nominales totales coincidan con la instantánea previa al incidente
- [ ] Presentar el informe final DORA dentro del mes siguiente a la resolución del incidente
