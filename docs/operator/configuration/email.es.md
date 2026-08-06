---
title: Configuración de correo electrónico
---

# Configuración de correo electrónico { #email-configuration }

El backend envía correos electrónicos para:
- Invitaciones de incorporación de clientes (con el enlace del token de un solo uso)
- Notificaciones de estado KYC (aprobado, rechazado, próximo a vencer)
- Correos electrónicos de bienvenida al activar la entidad

## Configuración SMTP { #smtp-configuration }

```dotenv
MAIL_HOST=smtp.sendgrid.net
MAIL_PORT=587
MAIL_USERNAME=apikey
MAIL_PASSWORD=SG.<your-key>
MAIL_FROM=noreply@yourregistry.de
```

## Plantillas de correo electrónico { #email-templates }

Las plantillas son archivos Thymeleaf HTML en:
```
backend/src/main/resources/infrastructure/email/templates/
├── welcome.html
├── onboarding-invite.html
├── kyc-approved.html
├── kyc-rejected.html
└── kyc-expiring.html
```

Personalice estas plantillas con su marca. Variables disponibles:
- `${entityName}`: nombre de la entidad jurídica
- `${onboardingUrl}`: enlace de incorporación (solo por invitación)
- `${registryUrl}`: URL base del frontend de cliente

## Prueba local { #testing-locally }

Para desarrollo local, use [Greenmail](https://greenmail-mail-test.github.io/greenmail/) (incluido en dependencias de prueba) o [Mailhog](https://github.com/mailhog/MailHog):

```bash
docker run -p 8025:8025 -p 1025:1025 mailhog/mailhog
```

Luego configure:
```dotenv
MAIL_HOST=localhost
MAIL_PORT=1025
```

Ver correos electrónicos en `http://localhost:8025`.
