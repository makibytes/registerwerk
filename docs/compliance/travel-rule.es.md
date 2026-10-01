---
title: Travel Rule (TFR)
description: Implementación IVMS-101 de la Travel Rule para transferencias de criptoactivos entre CASP/VASP.
---

# Travel Rule (TFR / IVMS-101) { #travel-rule-tfr-ivms-101 }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Esta página registra las asignaciones de control previstas y el comportamiento actual del repositorio. No es evidencia
    de que el operador o la transacción estén dentro del alcance, de que todos los datos requeridos se recopilen o
    intercambien, o de que una transferencia se ajuste a las reglas vigentes de la TFR/Travel Rule. El alcance, los
    umbrales, las contrapartes, las excepciones, la protección de datos y la evidencia del protocolo requieren una
    revisión externa actual.

El **Reglamento de Transferencias de Fondos (TFR)** — Reglamento (UE) 2023/1113 — se aplica en su totalidad desde el 30 de diciembre de 2024. Exige que la información del ordenante y del beneficiario (estructurada conforme al estándar **IVMS-101**) acompañe a **toda** transferencia de criptoactivos entre proveedores de servicios de criptoactivos (CASP), **con independencia del importe**. A diferencia de las transferencias bancarias en moneda fiduciaria, la TFR **no contiene un umbral de minimis** para las transferencias CASP a CASP — así lo confirman las directrices de la Travel Rule de la EBA (EBA/GL/2024/11). La cifra de 1.000 € en la TFR se refiere únicamente a las transferencias hacia/desde **direcciones autohospedadas (self-hosted)**: por encima de ese importe, el Art. 14(5) exige que el CASP de origen verifique que la dirección autohospedada es propiedad de su propio cliente o está bajo su control.

---

## Qué activa la Travel Rule { #what-triggers-the-travel-rule }

Se evalúa cada transferencia saliente de criptoactivos. Las obligaciones difieren según el tipo de contraparte:

1. **El monedero de destino pertenece a un CASP/VASP conocido** (mediante búsqueda en el directorio) → se debe transmitir la información completa del ordenante/beneficiario IVMS-101, **con cualquier importe**.
2. **El destino es una dirección autohospedada** → la información del ordenante se recopila y conserva localmente; por encima de 1.000 €, el CASP de origen debe verificar además la propiedad/control de la dirección (Art. 14(5) TFR).
3. Las transferencias entre dos monederos de la misma entidad legal en el mismo CASP quedan fuera del deber de transmisión CASP a CASP, pero aun así se registran.

Registerwerk verifica estas condiciones en `TravelRuleService.evaluate()` antes de ejecutar cualquier operación de `forceTransfer` o de mint externo.

---

## Estructura de datos IVMS-101 { #ivms-101-data-structure }

IVMS-101 (InterVASP Messaging Standard) define un formato estructurado para la información del ordenante y del beneficiario. El registro `Ivms101` de Registerwerk en `travelrule/api/` se corresponde con los campos de la Recomendación 16 del GAFI (FATF):

```java
public record Ivms101(
    Person originator,       // IVMS101 Person: name, geographicAddress, nationalIdentification
    Person beneficiary,      // IVMS101 Person: name, geographicAddress, nationalIdentification
    String originatorVasp,   // LEI or BIC of the originating VASP
    String beneficiaryVasp,  // LEI or BIC of the beneficiary VASP
    BigDecimal amount,
    String currency,
    String transferRef       // Unique transfer reference
) {}
```

El registro `Person` incluye el nombre de la persona física o jurídica, la dirección y una o más identificaciones nacionales (número de pasaporte, LEI, identificación fiscal).

---

## Flujo de transferencia { #transfer-flow }

```mermaid
sequenceDiagram
    participant Operator
    participant TravelRuleService
    participant VaspDirectory
    participant TravelRuleProtocolPort
    participant BeneficiaryVASP

    Operator->>TravelRuleService: forceTransfer(assetId, from, to, amount)
    TravelRuleService->>VaspDirectory: lookupVasp(toWalletAddress)
    VaspDirectory-->>TravelRuleService: VaspInfo (LEI, endpoint) or null
    alt Wallet belongs to known VASP
        TravelRuleService->>TravelRuleService: Build Ivms101 payload
        TravelRuleService->>TravelRuleProtocolPort: send(Ivms101)
        TravelRuleProtocolPort->>BeneficiaryVASP: IVMS-101 message
        BeneficiaryVASP-->>TravelRuleProtocolPort: ACK
        TravelRuleService->>TravelRuleService: Persist TravelRuleMessage (SENT)
    else Self-hosted address
        TravelRuleService->>TravelRuleService: Log exemption reason
    end
    TravelRuleService->>Blockchain: Execute on-chain transfer
```

---

## Adaptador de protocolo conectable { #pluggable-protocol-adapter }

Distintos VASP usan distintos protocolos de Travel Rule (TRP, Sygna Bridge, Notabene, OpenVASP). Registerwerk usa un puerto (`TravelRuleProtocolPort`) con una implementación no operativa (no-op) por defecto (`NoopTravelRuleAdapter`) y una ranura para adaptador conectable:

```java
public interface TravelRuleProtocolPort {
    void send(Ivms101 payload, String beneficiaryVaspEndpoint);
    TravelRuleMessage.Status getStatus(String transferRef);
}
```

Para habilitar un protocolo real en producción, implemente `TravelRuleProtocolPort` y regístrelo como Spring Bean. El `NoopTravelRuleAdapter` será desplazado automáticamente por cualquier bean concreto en el contexto de la aplicación.

---

## Mensajes entrantes de la Travel Rule { #inbound-travel-rule-messages }

Registerwerk también recibe mensajes de la Travel Rule de otros VASP cuando estos transfieren tokens a monederos administrados por Registerwerk. El endpoint de la bandeja de entrada:

```
POST /api/v1/public/travel-rule/inbox
```

Cada VASP par es registrado por un `REGISTRY_ADMIN` (`POST /api/v1/compliance/travel-rule/peers`, step-up y segundo aprobador) y recibe su propia clave HMAC, mostrada una sola vez. Una solicitud solo se acepta si se cumple todo lo siguiente:

- `X-Vasp-Id`, `X-Registerwerk-Timestamp` (segundos epoch, dentro de 5 minutos) y `X-Registerwerk-Peer-Signature` = HMAC-SHA256 hexadecimal sobre `timestamp|vaspId|sha256(body)` se verifican con la clave de ese par registrado, y la firma no se ha usado antes (caché anti-repetición);
- `originatingVasp.vaspId` del payload coincide con el par autenticado;
- el remitente no está bloqueado ni revocado en el registro CASP (una entrega rechazada se guarda como `REJECTED_CASP` y se audita).

Al recibirlo:

1. Se validan los números de cuenta y la referencia de transferencia; el cuerpo está limitado a 256 KiB.
2. El mensaje se guarda bajo el **par autenticado** con su hash de payload y `transferDetails`. Una reentrega del mismo payload es idempotente; un payload distinto con la misma referencia también se guarda, ambas filas reciben el estado `CONFLICT` y un evento de auditoría: un par ya no puede suprimir el mensaje de otro reclamando antes la referencia.
3. Los mensajes incompletos (faltan nombre, dirección o identificación del ordenante/beneficiario, TFR art. 16.1) reciben el estado `INCOMPLETE`. Una tarea en segundo plano vincula cada mensaje con el `token_transfer` indexado (`matched_transfer_id`). `GET /api/v1/compliance/travel-rule/open` lista todo lo que requiere a un operador.

!!! warning "Clave compartida"
    La antigua clave compartida `X-Travel-Rule-Api-Key` está obsoleta: solo funciona con `registerwerk.travel-rule.legacy-shared-key=true` fuera del modo de producción, y `X-Vasp-Id` entonces **no** está autenticado. Registerwerk no bloquea los tokens acreditados; si el CASP receptor de referencia es el operador o el custodio del titular es una cuestión jurídica abierta (aparcada T6-08).

---

## Directorio de VASP { #vasp-directory }

La interfaz `VaspDirectoryPort` admite el descubrimiento conectable de VASP:

- **Directorio TRP** (stub por defecto) — el registro global de VASP operado por el consorcio Travel Rule Protocol
- **Shyft Trust** — directorio de VASP alternativo
- Anulación local: los operadores pueden registrar asignaciones de VASP conocidas en el portal de administración

Las búsquedas de VASP se almacenan en caché durante 30 segundos usando la configuración de caché Caffeine existente.

---

## Matriz de obligaciones { #obligations-matrix }

| Escenario | Importe | Acción |
|---|---|---|
| Transferencia CASP a CASP | **Cualquier importe** | Se requiere transmisión IVMS-101 completa — sin de minimis (TFR Art. 14–16) |
| CASP a monedero autohospedado | ≤ 1.000 € | Recopilar y conservar la información del ordenante (`UNHOSTED_RECORDED`) |
| CASP a monedero autohospedado | > 1.000 € | Bloquear la ejecución hasta verificar la propiedad/control de la dirección (Art. 14(5)) — `UNHOSTED_VERIFY_REQUIRED` |
| Autocustodia de la misma entidad | Cualquier importe | Fuera del deber de transmisión CASP a CASP — se registra |
| Contraparte CASP sin adaptador de protocolo configurado | Cualquier importe | **La transferencia se rechaza (denegación por defecto / fail closed)** — ejecutarla sin la información exigida infringiría el Art. 14 |

Ningún llamador aporta actualmente una valoración en EUR: un valor desconocido se trata como superior a 1.000 € (fail closed; fuente de valoración aparcada T6-06), por lo que una prueba de control de monedero (véase abajo) libera una transferencia a un monedero autoalojado registrado de un titular. La valoración se usa **solo** como desencadenante del art. 14.5, nunca para omitir el mensaje CASP a CASP.


---

## Verificación de la autorización MiCA de la contraparte { #mica-counterparty-authorization-check }

El período transitorio de MiCA en toda la UE finaliza el **1 de julio de 2026** (declaración de la ESMA, 17 de abril de 2026) — ningún Estado miembro puede prorrogar los derechos adquiridos (grandfathering) más allá de esa fecha. A partir de esa fecha límite, prestar servicios de criptoactivos en la UE sin autorización CASP constituye una infracción del derecho de la UE, y las transferencias a esas contrapartes no deben ejecutarse.

Registerwerk hace cumplir esto mediante el **Registro de Autorización CASP** (`/api/v1/compliance/casp-register`, en la UI del operador bajo *Compliance → CASP Register*). Los compliance officers reflejan el estado del registro ESMA/NCA de cada contraparte de la Travel Rule:

| Estado de contraparte | Antes del 1 de julio de 2026 | A partir del 1 de julio de 2026 |
|---|---|---|
| `AUTHORIZED` | Permitido (bloqueado si `validUntil` ya venció) | Permitido (bloqueado si `validUntil` ya venció) |
| `TRANSITIONAL` | Permitido | **Bloqueado** — sin derechos adquiridos |
| `NOT_AUTHORIZED` / `REVOKED` | **Bloqueado** | **Bloqueado** |
| Sin entrada de registro | Permitido con advertencia | **Bloqueado** (fail closed); los VASP no UE requieren una entrada revisada `THIRD_COUNTRY_REVIEWED` |

Los intentos bloqueados se registran en `travel_rule_message` con el estado `BLOCKED_MICA` antes de rechazar la transferencia, de modo que el registro de auditoría muestra el intento de transferencia y el motivo regulatorio. La fecha límite es configurable mediante `registerwerk.travel-rule.mica-enforcement-date`.

## Enriquecimiento de identidad IVMS-101 { #ivms-101-identity-enrichment }

Las cargas salientes se enriquecen a partir del registro de titulares de activos: el monedero del ordenante se resuelve al titular registrado (`asset_holder` → `legal_entity`) y el registro IVMS-101 incorpora el nombre legal (`LEGL`), el LEI como identificación nacional `LEIX` cuando está presente, el número de entidad como identificación de cliente, y el país de residencia — conforme al Art. 14(1) de la TFR, la dirección del monedero por sí sola no satisface los requisitos de información. El lado del beneficiario solo se enriquece en las transferencias internas al registro; para beneficiarios externos, la identidad la conserva el CASP de contraparte.

## Importación masiva del registro CASP { #bulk-import-of-the-casp-register }

`POST /api/v1/compliance/casp-register/import` (UI del operador: *Cumplimiento → Registro CASP → Importar CSV*) acepta una CSV con las columnas canónicas `legal_name`, `vasp_did` (o `lei`, de las cuales se sintetiza `lei:<LEI>`), `status`, y opcionalmente `home_member_state`, `authorization_id`, `valid_from`, `valid_until`, `notes`. El mapeo de estado tolera la ortografía británica de ESMA ("Autorizado") y asigna "Retirado" a `REVOKED`. La importación se realiza con el mejor esfuerzo por fila: las filas válidas se insertan con la clave `vaspDid`, las fallas se informan por línea.


## Entrega, pruebas y controles del registro { #delivery-proofs-register-controls }

!!! note "La entrega saliente se espera"
    Para un beneficiario CASP primero se confirma la fila `PENDING_SEND`, se envía el mensaje y se espera (`registerwerk.travel-rule.send-timeout-seconds`, 15 por defecto); solo un `SENT` confirmado permite enviar la transferencia forzosa on-chain. Un fallo o tiempo de espera guarda `FAILED`, rechaza la operación, que puede repetirse sin más; no hay anulación (aparcado T6-06). Las filas que permanecen más de 5 minutos en `PENDING_SEND` las marca como `FAILED` un sweeper con alerta. La misma puerta se aplica a las transferencias forzosas ERC-20/721/1155 y ERC-3643.

El mensaje incluye el VASP propio del operador (`registerwerk.travel-rule.own-vasp.did`/`lei`/`legal-name`, obligatorio en producción), el VASP beneficiario resuelto desde el directorio (se usa su propio endpoint: solo https, sin direcciones privadas, `trp.allowed-hosts` opcional) y `transferDetails` (cantidad y símbolo del token, contrato, fecha de ejecución). Si faltan datos obligatorios del ordenante o del beneficiario, la transferencia se detiene con el estado `INCOMPLETE_IVMS` y no se envía nada.

**Pruebas de control de monedero (art. 14.5).** Una transferencia a un monedero autoalojado de un titular se libera cuando existe una prueba válida para ese par exacto (entidad jurídica, monedero): un desafío de mensaje firmado (`POST /api/v1/compliance/travel-rule/wallet-proofs/challenges`, luego `/{id}/signature`) o una atestación de operador con nota de evidencia obligatoria (step-up y segundo aprobador). El mensaje se registra como `UNHOSTED_VERIFIED` con el id de la prueba. Sin prueba la transferencia sigue bloqueada y el error indica el endpoint. La exención interna del registro (`registerwerk.travel-rule.register-internal-exempt`) está desactivada por defecto porque es una posición jurídica (aparcado T6-06).

**Registro CASP.** La búsqueda se hace por DID, luego LEI y luego una denominación única. Las modificaciones, eliminaciones e importaciones requieren step-up y segundo aprobador; levantar un estado `NOT_AUTHORIZED`/`REVOKED` o eliminar esa fila exige un `REGISTRY_ADMIN` como aprobador. La importación CSV es en dos pasos: `POST /casp-register/import/preview` devuelve la diferencia y un `diffDigest`, `POST /casp-register/import?diffDigest=...` la confirma. Una entrada `THIRD_COUNTRY_REVIEWED` requiere revisor, segundo aprobador y fecha de caducidad.

!!! warning "Supuestos jurídicos"
    Si el TFR se aplica a los valores criptográficos eWpG, si las transferencias ordenadas judicialmente o internas del registro están exentas, la fuente de valoración en EUR y el tratamiento de los VASP no UE son decisiones aparcadas (T6-06, T6-07). El comportamiento descrito es la solución provisional prudente, no una valoración jurídica.
