---
title: Conceptos fundamentales
description: Glosario de los términos jurídicos, financieros y técnicos usados en todo Registerwerk.
---

# Conceptos fundamentales

Este glosario define los términos empleados en la documentación, el código y las interfaces de Registerwerk. Los términos se agrupan por ámbito; las referencias cruzadas apuntan a páginas detalladas cuando existen.

---

## Valores y emisión

**Token de valor (security token)**
: Un token de blockchain que representa un instrumento financiero — un bono, una acción, una participación de fondo u otro activo regulado. Registerwerk gestiona tokens de valores sujetos al derecho del mercado de valores de las [jurisdicciones admitidas](../legal/index.md).

**Valor electrónico (elektronisches Wertpapier)**
: Un valor que existe exclusivamente como anotación en un registro electrónico central o descentralizado, sin documento en papel. Definido en Alemania por el [§2 eWpG](../legal/ewpg.md); existen equivalentes en el derecho luxemburgués, francés y liechtensteiniano.

**Emisor**
: La entidad jurídica que crea y ofrece un token de valor. En Registerwerk, un emisor es una entidad jurídica [cliente](#entidades-clientes) con la función `ISSUER` que ha superado la aprobación [KYC/prevención del blanqueo](../compliance/kyc-aml.md).

**Inversor / titular**
: Una entidad jurídica o persona física que mantiene una posición en un token de valor. Se sigue en el sistema como registro `AssetHolder` enlazado mediante una `HolderIdentity` a una `LegalEntity` o a una `NaturalPerson`.

**ISIN** (International Securities Identification Number)
: Un código alfanumérico de 12 caracteres que identifica un valor de forma única en todo el mundo. Registerwerk guarda el ISIN en la entidad `Asset` y lo incorpora a los metadatos del token.

**Número de activo**
: El identificador secuencial interno de Registerwerk para un valor, distinto del ISIN. Se usa en flujos internos y referencias de auditoría.

**Emisión / despliegue**
: El acto de crear un contrato de token en una blockchain. En Registerwerk el despliegue se sigue como registro `AssetDeployment`, que enlaza el `Asset` fuera de cadena con su dirección de contrato on-chain.

---

## Conceptos de blockchain

**Blockchain / cadena**
: Una red de libro distribuido. Registerwerk admite Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism (EVM), Solana, StarkNet, Stellar y Canton. Véase [Blockchains admitidas](../blockchains/index.md).

**Estándar de token**
: Una especificación que define la interfaz de un token (cómo puede transmitirse, consultarse y administrarse). Ejemplos: ERC-20, ERC-3643, SPL-2022. Véase [Estándares de token](../token-standards/index.md).

**Contrato inteligente**
: Código ejecutable desplegado en una blockchain. Registerwerk despliega contratos mediante [Web3j](https://web3j.io/) (EVM) y Solanaj (Solana). Las direcciones de contrato se guardan en `AssetDeployment`.

**Transacción (on-chain)**
: Una operación firmada criptográficamente y enviada a una blockchain. Todo cambio de estado se anota como `BlockchainTransaction` y se enlaza con el evento de auditoría correspondiente.

**Desviación de cadena**
: Una discrepancia entre el saldo de tokens on-chain y el campo `AssetHolder.nominalAmount` de la base de datos de Registerwerk. El `ChainDriftDetectionJob` comprueba la desviación cada 15 minutos para cada activo emitido.

**Registro determinante**
: Registerwerk mantiene una anotación operativa de titulares en PostgreSQL y proyecta o concilia determinado estado on-chain. Qué anotación tiene autoridad jurídica depende del instrumento, del modelo de registro, del operador y de la jurisdicción, y requiere una decisión de perímetro aprobada. Ni la base de datos ni la blockchain son determinantes de forma universal.

**Monedero**
: Un par de claves criptográficas usado para firmar transacciones on-chain. Registerwerk gestiona los monederos del operador (material de claves cifrado en reposo) mediante el módulo `wallet`.

---

## Regulación y cumplimiento

**KYC** (Know Your Customer)
: El proceso de verificar la identidad de un cliente — incluidos su negocio, sus propietarios y sus titulares reales — antes de establecer una relación de negocio. Véase [KYC y prevención del blanqueo](../compliance/kyc-aml.md).

**KYB** (Know Your Business)
: El equivalente societario del KYC, centrado en verificar la legitimidad y la estructura de propiedad de una entidad jurídica.

**Prevención del blanqueo (AML)**
: El conjunto de normas que obliga a las empresas a detectar y prevenir el blanqueo de capitales. En Alemania: la GwG; en la UE: la AMLD6 y el futuro AMLR.

**PEP** (persona con responsabilidad pública)
: Una persona que desempeña o ha desempeñado una función pública relevante. Las PEP exigen diligencia debida reforzada conforme al [§10(2) GwG](../compliance/kyc-aml.md).

**Titular real último (UBO)**
: La persona o personas físicas que en última instancia poseen o controlan una entidad jurídica, normalmente a partir de un umbral del 25 %. Se sigue en Registerwerk como `BeneficialOwner` enlazado a una `NaturalPerson`.

**Filtrado de sanciones**
: El cotejo de una persona o entidad con las listas internacionales de sanciones (OFAC SDN, PESC de la UE, ONU 1267, HMT del Reino Unido, SECO suiza). Véase [Filtrado de sanciones](../compliance/sanctions-screening.md).

**Travel Rule (TFR)**
: El Reglamento (UE) 2023/1113, que exige que la información de ordenante y beneficiario acompañe a las transferencias de criptoactivos superiores a 1.000 € entre PSAV. Implementado con el [estándar de datos IVMS-101](../compliance/travel-rule.md).

**PSAV** (proveedor de servicios de activos virtuales)
: Una empresa regulada que presta servicios relativos a activos virtuales (plataformas de intercambio, depositarios). El propio Registerwerk actúa como PSAV/PSC cuando emite tokens por cuenta de terceros.

**PSC** (proveedor de servicios de criptoactivos)
: El término que MiCAR emplea para PSAV en el derecho de la UE.

**Sperrvermerk**
: Término jurídico alemán para una anotación de bloqueo sobre una inscripción del registro de valores, que restringe la transmisión o grava un activo. Impuesto por el [§16 eWpG](../legal/ewpg.md). Véase [Sperrvermerk](../compliance/sperrvermerk.md).

**DORA** (Digital Operational Resilience Act)
: El Reglamento (UE) 2022/2554, que obliga a las entidades financieras a gestionar los riesgos tecnológicos, notificar los incidentes graves y llevar un registro de proveedores tecnológicos terceros. Véase [DORA](../compliance/dora.md).

**LEI** (identificador de entidad jurídica)
: Un código de 20 caracteres conforme a la norma ISO 17442 que identifica de forma única a una entidad jurídica en todo el mundo. Se guarda en `LegalEntity` en Registerwerk; recomendado para todos los emisores.

---

## Entidades clientes

**Operador**
: La organización que explota una instalación de Registerwerk. Los operadores acceden al front end del operador (:4200) y pueden administrar todos los clientes, activos y datos de cumplimiento.

**Cliente**
: Un emisor o inversor dado de alta por un operador. Los clientes acceden al front end del cliente (:4201) a través de la pasarela de API Kong.

**Entidad jurídica (`LegalEntity`)**
: El modelo de datos central de la sociedad de un cliente. Contiene la jurisdicción, el número de inscripción, el LEI, el estado KYC y los enlaces a titulares reales y documentos KYC.

**Persona física (`NaturalPerson`)**
: Un individuo — administrador, titular real o inversor. La entidad actual coloca los datos personales como nombre, fecha de nacimiento, nacionalidad y número fiscal en columnas de base de datos ordinarias; no está implementado el cifrado de campos a nivel de aplicación.

**Titular real (`BeneficialOwner`)**
: Sirve de puente entre una `LegalEntity` y una `NaturalPerson`, con porcentaje de participación y tipo de control.

---

## Términos propios de la plataforma

**Módulo**
: Un contexto delimitado de Spring Modulith. Registerwerk tiene 34 módulos, cada uno con un paquete `api/` (tipos públicos) y un paquete `internal/` (implementación privada). Véase [Arquitectura de módulos](../platform/modules.md).

**Autenticación reforzada (step-up)**
: Un segundo desafío de autenticación exigido antes de ejecutar operaciones de alto riesgo (transferencia forzosa, destrucción forzosa, excepción de KYC). Impuesto por la anotación `@RequiresStepUp`. Véase [MFA reforzada](../compliance/step-up-mfa.md).

**Principio de doble control (Vier-Augen-Prinzip)**
: Un requisito de control dual por el que un segundo aprobador autorizado debe confirmar una actuación antes de que surta efecto. Implementado mediante el módulo `stepup`.

**Cadena de auditoría**
: La secuencia a prueba de manipulación de eventos de auditoría, cada uno con un hash de la anotación anterior. Aporta prueba criptográfica de la integridad y exhaustividad de la pista de auditoría. Véase [Pista de auditoría](../platform/audit-log.md).
