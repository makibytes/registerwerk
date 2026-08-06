---
title: Panel
---

# Panel

El panel es la primera pantalla que ve tras acceder. Ofrece una visión en tiempo real de su actividad en el registro, adaptada a su función.

## Tarjetas de resumen

En la parte superior del panel encontrará tarjetas de resumen. Cuáles se muestran depende de su función:

### Panel del emisor

| Tarjeta | Descripción |
|------|-------------|
| **Active Issuances** | Número de tokens actualmente en estado ISSUED |
| **Pending Approval** | Emisiones a la espera de revisión del operador |
| **Total Investors** | Monederos de inversores distintos en todos sus tokens |
| **Networks** | Redes blockchain distintas en las que ha desplegado tokens |

### Panel del inversor

| Tarjeta | Descripción |
|------|-------------|
| **Token Holdings** | Número de tokens de valores distintos que mantiene |
| **Connected Wallets** | Monederos registrados en su cuenta |
| **Recent Transfers** | Transmisiones de los últimos 30 días |

### Panel del auditor

| Tarjeta | Descripción |
|------|-------------|
| **Total Issuances** | Todas las emisiones del registro |
| **Transfers (30d)** | Total de eventos de transmisión on-chain de los últimos 30 días |
| **Active Issuers** | Número de entidades emisoras con al menos un token activo |
| **Pending KYC Reviews** | Expedientes KYC pendientes de revisión del operador (solo lectura) |

## Actividad reciente

Bajo las tarjetas de resumen, el panel **Recent Activity** muestra los últimos eventos relevantes para su cuenta. Cada entrada incluye:

- **Marca de tiempo** — cuándo ocurrió el evento (en su zona horaria local)
- **Tipo de evento** — por ejemplo *Issuance Created*, *Transfer*, *KYC Approved*
- **Objeto** — el token o la entidad implicados
- **Red** — la red blockchain (con el icono de la cadena)

Haga clic en cualquier fila de actividad para ir directamente a la página de detalle correspondiente.

## Acciones rápidas

El panel **Quick Actions** ofrece navegación de un clic a las tareas más habituales de su función:

- **Emisor**: New Issuance, Manage Investors, View Pending Approvals
- **Inversor**: View Holdings, Connect Wallet, Download Statement
- **Auditor**: Open Audit Log, Search Transfers, Export Report

## Estado de las redes

En la parte inferior del panel, una rejilla **Network Status** en vivo indica si cada red blockchain configurada está actualmente accesible y sincronizada. El verde significa que el indexador está al día; el amarillo, que va más de 10 bloques por detrás de la punta de la cadena; el rojo, que no está disponible.

!!! tip
    Si una red aparece en rojo, los datos on-chain de esa red pueden estar caducados. Espere unos minutos y actualice. Si el problema persiste, contacte con el operador del registro.


## Actualizar los datos

Los datos del panel se actualizan automáticamente cada 30 segundos. Puede forzar una actualización inmediata con el botón **Refresh** de la esquina superior derecha de cada panel.
