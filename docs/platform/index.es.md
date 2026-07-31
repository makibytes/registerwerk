---
title: Arquitectura de plataforma
description: Arquitectura interna del backend de Registerwerk: módulos, seguridad, auditoría y API.
---

# Arquitectura de plataforma { #platform-architecture }

Esta sección cubre el diseño interno de la plataforma Registerwerk para ingenieros y operadores.

- [Arquitectura de módulos](modules.md) — 22 contextos delimitados de Spring Modulith, gráfico de dependencias
- [Seguridad y autenticación](security.md) — JWT, OIDC, aplicación de roles, protecciones de fallo rápido (fail-fast)
- [Registro de auditoría](audit-log.md) — cadena hash a prueba de manipulaciones, administración de particiones
- [Descripción general de la API REST](api.md) — estructura de URL, respuestas de error, paginación
- [Desarrollo de dApp](dapp-development.md) — marco de permisos del ecosistema, flujo de trabajo de publicación en el mercado
- [Interoperabilidad DeFi](defi-interoperability.md) — preguntas de jurisdicción, puente nominado/ómnibus, y una facilidad de referencia de repo/préstamo que no está aprobada para uso en producción
- [Abstracción de cuenta y transacciones patrocinadas](account-abstraction.md) — hoja de ruta ERC-4337/EIP-7702, patrocinio de gas, claves de acceso
