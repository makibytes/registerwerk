---
title: Ledger Canton / DAML
description: Integración del ledger privado Canton y DAML Finance para instrumentos de bonos regulados.
---

# Ledger Canton / DAML

Canton es un **ledger distribuido con la privacidad como principio rector**, desarrollado por Digital Asset. A diferencia de las cadenas de bloques públicas, Canton implementa **privacidad por subtransacción**: cada participante solo ve los contratos en los que es parte. Esto hace que Canton resulte atractivo para instrumentos institucionales en los que las posiciones no deben ser visibles para otros participantes del mercado.

---

## Conceptos de la arquitectura de Canton

| Concepto | Canton | Correspondencia en Registerwerk |
|---|---|---|
| **Ledger** | El ledger distribuido de Canton | Un nodo participante de Canton Network por operador del registro |
| **Party** | Una identidad criptográfica única en el ledger | `LegalEntity.cantonPartyId` |
| **Contract** | Una instancia de contrato DAML | Uno por bono o por posición en un activo |
| **Choice** | Una acción que puede ejercerse sobre un contrato | Operación societaria (cupón, amortización) |
| **Synchroniser** | El componente de consenso | Sincronizador global de Canton Network |
| **Ledger API** | API gRPC para interactuar con Canton | `CantonLedgerEndpoint` |

---

## Tipos de bonos de DAML Finance

Véase [DAML Finance Bonds](../token-standards/canton-daml.md) para el tratamiento completo de la configuración de las condiciones del bono y de los pagos de cupón.

---

## Configuración de la conexión

`CantonLedgerEndpoint` se conecta a un nodo participante de Canton a través de su **Ledger API** (gRPC):

```yaml
registerwerk:
  canton:
    mainnet:
      ledgerApiUrl: "participant.example.com:5001"
      synchronizerId: "global-synchronizer"
      applicationId: "registerwerk"
      authToken: "${CANTON_MAINNET_TOKEN}"  # JWT for participant auth
    devnet:
      ledgerApiUrl: "localhost:5001"
      synchronizerId: "dev-synchronizer"
```

Para Canton Network (la Canton pública): obtenga un nodo participante del operador de Canton Network, registre su aplicación y facilite la URL de la Ledger API.

Para desarrollo: hay disponible un sandbox local de Canton mediante `docker compose -f indexer/canton/docker-compose.yml up`.

---

## Asignación de parties

Antes de que un cliente pueda participar en instrumentos basados en Canton, se le debe asignar una **Canton Party**. De ello se encarga `CantonPartyAllocator.allocate(entityId)`:

1. Llama a `PartyManagementService.allocateParty()` de la Ledger API
2. Almacena el identificador de party devuelto en `LegalEntity.cantonPartyId`
3. El identificador de party se utiliza en todas las referencias a contratos DAML de esa entidad

Las parties son inmutables una vez asignadas; una party nunca puede reutilizarse para otra entidad.

---

## Modelo de privacidad

La privacidad de Canton se aplica en el propio ledger:

- El **emisor** ve: todos los contratos de sus instrumentos
- El **inversor** ve: únicamente los contratos de sus propias posiciones
- El **operador del registro** ve: todos los contratos (en el rol de observer de DAML)
- **Otros inversores**: no pueden ver las posiciones de los demás inversores

Se trata de privacidad nativa sin cifrado: la infraestructura del ledger garantiza que los datos de un contrato solo se transmitan a las parties que son partes interesadas en ese contrato.

---

## El perfil de Maven `-Pcanton`

Dado que el SDK de DAML y los JAR asociados son grandes y no están en Maven Central, la compatibilidad con Canton está condicionada al perfil `-Pcanton`:

```bash
./mvnw verify -Pcanton          # includes Canton
./mvnw verify                   # Canton disabled, stub injected
```

Sin `-Pcanton` se utiliza `CantonBondDisabledStub`. Las llamadas a la API relativas a instrumentos basados en Canton devuelven `503 Service Unavailable` con un mensaje que explica que la compatibilidad con Canton requiere el perfil `-Pcanton` y un nodo participante en ejecución.

---

## Indexador

El indexador de Canton utiliza el **Transaction Service** de la Ledger API para transmitir en flujo todas las transacciones confirmadas. Procesa:
- Contratos de emisión de bonos → crea registros `AssetHolder`
- Eventos de pago de cupón → crea registros `token_transfer` de tipo `COUPON`
- Eventos de transferencia → actualiza `AssetHolder.nominalAmount`

La liveness del indexador de Canton la supervisa `IndexerMonitorService`, igual que en los indexadores de EVM y Solana.
