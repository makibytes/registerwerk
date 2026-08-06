---
title: Preguntas frecuentes
---

# Preguntas frecuentes

## General

### ¿Qué es el registro eWpG?

Registerwerk es una implementación de referencia para crear y administrar anotaciones de valores electrónicos y los tokens de blockchain asociados. Que un instrumento esté jurídicamente reconocido conforme a la ley alemana de valores electrónicos (Elektronisches Wertpapiergesetz — eWpG) depende del instrumento, del modelo de registro, del operador y de la instalación, y debe examinarse externamente.

### ¿Está regulado el registro?

La autorización es específica de cada instalación y operador. Este repositorio no contiene prueba alguna de que un operador determinado disponga de una autorización regulatoria exigida. Verifique las actividades previstas, las autorizaciones del operador y la estructura del instrumento con asesoramiento cualificado y con el operador correspondiente antes de usarlo.

### ¿Puedo registrarme por mi cuenta?

No. El alta la inicia el operador. Contacte con el operador del registro para solicitarla. Así se garantiza que todos los participantes estén verificados antes de acceder a la plataforma.

---

## Emisores

### ¿Cuánto tarda el proceso de aprobación?

El plazo de revisión depende del operador y del caso. Este repositorio no define ni garantiza un nivel de servicio de 1 a 3 días hábiles; pregunte al operador responsable por el procedimiento y los plazos aplicables.

### ¿Puedo cambiar los parámetros del token tras la aprobación?

No. Una vez que una emisión está en estado APPROVED, todos los parámetros (nombre, ISIN, cadena, estándar de token, volumen total) quedan bloqueados. Puede retirar el envío y volver a DRAFT para introducir cambios.

### ¿Qué significa «onchain level»?

Determina qué parte de su lógica de cumplimiento reside en la blockchain:
- **None** — solo anotación registral, sin contrato inteligente desplegado
- **Simple** — contrato de token estándar desplegado, sin aplicación de cumplimiento
- **Control** — contrato ERC-3643 desplegado con módulos de cumplimiento on-chain

### ¿Puedo desplegar en varias cadenas?

Actualmente cada emisión se despliega en una sola red. Para emitir el mismo valor en varias cadenas, crearía emisiones separadas con el mismo ISIN. Contacte con el operador del registro si necesita soporte multicadena.

### ¿Qué ocurre con mi token si el registro se cae?

Una vez desplegado un token, el contrato puede seguir existiendo con independencia de esta aplicación, según la red elegida y los controles del contrato. Registerwerk mantiene una anotación operativa de titulares y proyecta o concilia determinado estado on-chain. Qué anotación tiene autoridad jurídica depende del instrumento, del modelo de registro y de la jurisdicción, y requiere una decisión de perímetro aprobada; un saldo indexado u on-chain no es, por sí solo, prueba de titularidad ni de efecto jurídico.

---

## Inversores

### ¿Necesito un monedero especial para mantener tokens de valores?

Para tokens ERC-20 sirve cualquier monedero EVM estándar (MetaMask, Ledger, etc.). Para tokens ERC-3643 sirve también cualquier monedero EVM compatible con ERC-20 — la lógica de cumplimiento está en el contrato, no en el monedero. Para tokens ERC-3643 confidenciales necesita un monedero compatible con FHE en la red Fhenix o Inco.

### ¿Por qué no puedo recibir tokens en mi dirección de monedero?

Las causas más habituales son:
1. Su monedero no ha sido admitido por el emisor
2. Sus atestaciones KYC/prevención del blanqueo han caducado — compruebe **Profile → Identity**
3. Su país está restringido por un módulo de cumplimiento de ese token
4. El token está suspendido en este momento

### ¿Cómo consigo la aprobación del KYC?

El operador del registro gestiona el proceso KYC. Se le guiará en la entrega de documentos durante el alta. Si su KYC está pendiente o ha caducado, vaya a **Profile → Identity → Renew KYC**.

### ¿Son públicas mis tenencias de tokens?

Para tokens ERC-20, ERC-721, ERC-1155 y ERC-3643 estándar: sí, su saldo es visible en la blockchain pública para cualquiera que conozca su dirección de monedero. Para tokens ERC-3643 confidenciales: no, su saldo está cifrado on-chain.

---

## Auditores

### ¿Pueden los auditores iniciar transacciones?

No. La función de auditor es estrictamente de solo lectura. Ninguna actuación de un auditor puede modificar una anotación del registro ni desencadenar una transacción on-chain.

### ¿Cómo verifico que los datos del registro coinciden con la blockchain?

Toda anotación de transmisión en el registro incluye el hash de la transacción on-chain. Con ese hash puede verificar de forma independiente cualquier transmisión en el explorador de bloques correspondiente. Véase [la guía del auditor](workspaces/auditor.md) para el detalle.

### ¿Puedo exportar datos de auditoría a mis propios sistemas?

Sí. La pista de auditoría y las vistas de historial de tokens admiten exportaciones en CSV y JSON. Para rangos de fechas amplios, las exportaciones se generan de forma asíncrona y se envían a su correo.

---

## Técnica

### ¿Qué blockchains se admiten?

Cadenas EVM (Ethereum, Polygon, Base), Solana, Canton, StarkNet, Stellar y redes EVM confidenciales. También hay redes de prueba disponibles (Sepolia, Amoy, Base Sepolia, Solana Devnet). Véase [Blockchains admitidas](../blockchains/index.md) para la lista completa y para qué sirve cada una.

### ¿Qué estándares de token se admiten?

ERC-20, ERC-721, ERC-1155, ERC-3525, ERC-3643, ERC-4626, ERC-7540, sus variantes confidenciales, Solana SPL-2022 y bonos DAML Finance sobre Canton. Véase [Elegir un estándar de token](./issuers/token-standards.md) para orientarse.

### ¿Cómo accedo a la API?

La API REST está disponible en `https://api.registerwerk.example.com`. La documentación está en `/swagger-ui.html`. Para autenticarse necesita un token JWT de su proveedor de identidad. Véase [Acceder](./authentication.md).
