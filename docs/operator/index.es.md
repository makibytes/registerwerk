---
title: Para operadores
description: Explotar un registro Registerwerk — el oficio, la arquitectura y los procesos de cara al cliente que constituyen la mayor parte del trabajo.
---

# Para operadores

**Usted explota el registro.** Los clientes emiten valores en él, los mantienen, los negocian. Su trabajo es decidir quién entra, comprobar qué hacen, mantener viva la plataforma y ayudar cuando algo va mal.

No necesita conocer los mercados de valores con la profundidad de un emisor. Sí necesita entender lo bastante para saber qué está aprobando y por qué importa.

---

## Por dónde empezar

<div class="grid cards" markdown>

-   :material-flag:{ .lg .middle } **[Qué hace un operador](getting-started.md)**

    ---

    El oficio completo, el portal y las decisiones que solo le corresponden a usted.

-   :material-sitemap:{ .lg .middle } **[Cómo está construido Registerwerk](architecture.md)**

    ---

    La arquitectura, planteada desde lo que se rompe y lo que eso significa cuando ocurre.

-   :material-account-group:{ .lg .middle } **[Atender a los clientes](customers/index.md)**

    ---

    Alta, KYC, aprobaciones, soporte, modo soporte, baja. La mayor parte del trabajo real.

-   :material-server:{ .lg .middle } **[Instalación](installation/prerequisites.md)**

    ---

    Ponerlo en marcha, desde los requisitos hasta la pasarela.

</div>

---

## Las cuatro cosas que solo usted puede hacer

Los clientes pueden hacer muchísimo. Estas cuatro son suyas, y lo son porque cada una puede causar un daño difícil o imposible de revertir.

| | | |
|---|---|---|
| **Admitir una organización** | Nadie usa el registro hasta que usted aprueba su entidad y su KYC. | [Alta](customers/onboarding-flow.md) · [KYC](customers/kyc-process.md) |
| **Aprobar una emisión** | Ningún valor existe hasta que usted dice que sí. | [Aprobar emisiones](customers/approving-issuances.md) |
| **Rectificar el registro** | Transferencias forzosas, destrucciones forzosas, bloqueos de titular — las facultades de los §24 y §26 eWpG. | [Sperrvermerk](../compliance/sperrvermerk.md) |
| **Actuar como un cliente** | El modo soporte. Potente y plenamente atribuido. | [Modo soporte](customers/impersonation.md) |

Es sobre la segunda y la cuarta donde los operadores nuevos piden orientación más a menudo; ambas tienen página propia.

---

## Los hábitos que conviene adquirir pronto

!!! tip "Lea la pista de auditoría cuando no pasa nada"
    Si solo la abre durante un incidente, no sabrá qué aspecto tiene lo normal y no advertirá aquello que no debería estar ahí.

!!! tip "Trate el doble control como una función, no como un estorbo"
    Varias operaciones exigen una segunda persona: revertir una operación liquidada, aprobar la liquidación de una operación societaria, restablecer el MFA de un cliente, emitir un pase de acceso temporal. Son exactamente aquellas en las que una única actuación errónea o malintencionada resulta peor.

    Las instalaciones donde una sola persona posee todas las credenciales tienen doble control solo de nombre. Es la dotación de personal lo que lo hace real.

!!! tip "Diga «no lo sé» en voz alta"
    Le preguntarán si un instrumento cumple, si un token tiene eficacia jurídica, si un cliente puede hacer algo lícitamente. La plataforma modela reglas; no las dirime.

    Remitir una pregunta a los abogados es la respuesta correcta mucho más a menudo de lo que los operadores esperan.

---

## Qué no es usted

Conviene decirlo, porque los clientes supondrán lo contrario.

- **No es su abogado.** Aprueba según sus propios criterios, no los de ellos.
- **No es su depositario.** No puede recuperar una clave de monedero perdida. Puede ejecutar una transferencia forzosa del §24, que es una rectificación formal, no un restablecimiento de contraseña.
- **No es un servicio de valoración.** El registro anota importes nominales, no precios de mercado.
- **No es un garante.** Si un emisor incumple, usted lo hace constar; no resarce a los titulares.

---

## Cuando algo va mal

| | |
|---|---|
| La plataforma se comporta mal | [Resolución de problemas](troubleshooting.md) |
| Algo está caído | [Supervisión](maintenance/monitoring.md) · [Manual de recuperación](dr/runbook.md) |
| Cliente bloqueado fuera | [Soporte de doble factor](customers/two-factor-support.md) |
| Cliente desorientado | [Modo soporte](customers/impersonation.md) — ver exactamente lo que él ve |
| Defectos conocidos | [Registro de hallazgos](../assurance-review-ledger.md) |
