---
title: Configurazione e-mail
---

# Configurazione e-mail { #email-configuration }

Il backend invia e-mail per:
- Inviti di onboarding del cliente (con il collegamento token monouso)
- Notifiche sullo stato KYC (approvato, rifiutato, in scadenza)
- E-mail di benvenuto all'attivazione dell'entità

## Configurazione SMTP { #smtp-configuration }

```dotenv
MAIL_HOST=smtp.sendgrid.net
MAIL_PORT=587
MAIL_USERNAME=apikey
MAIL_PASSWORD=SG.<your-key>
MAIL_FROM=noreply@yourregistry.de
```

## Modelli email { #email-templates }

I modelli sono file Thymeleaf HTML in:
```
backend/src/main/resources/infrastructure/email/templates/
├── welcome.html
├── onboarding-invite.html
├── kyc-approved.html
├── kyc-rejected.html
└── kyc-expiring.html
```

Personalizza questi modelli con il tuo marchio. Variabili disponibili:
- `${entityName}` - nome del soggetto giuridico
- `${onboardingUrl}` - collegamento di onboarding (solo su invito)
- `${registryUrl}` - URL di base del frontend cliente

## Test locale { #testing-locally }

Per lo sviluppo locale, utilizzare [Greenmail](https://greenmail-mail-test.github.io/greenmail/) (incluso nelle dipendenze di test) o [Mailhog](https://github.com/mailhog/MailHog):

```bash
docker run -p 8025:8025 -p 1025:1025 mailhog/mailhog
```

Quindi impostare:
```dotenv
MAIL_HOST=localhost
MAIL_PORT=1025
```

Visualizza le email a `http://localhost:8025`.
