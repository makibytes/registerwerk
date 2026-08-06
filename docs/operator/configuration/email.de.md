---
title: E-Mail-Konfiguration
---

# E-Mail-Konfiguration { #email-configuration }

Das Backend sendet E-Mails für:
- Kunden-Onboarding-Einladungen (mit dem einmaligen Token-Link)
- KYC-Statusbenachrichtigungen (genehmigt, abgelehnt, ablaufend)
- Willkommens-E-Mails bei Entitätsaktivierung

## SMTP-Konfiguration { #smtp-configuration }

```dotenv
MAIL_HOST=smtp.sendgrid.net
MAIL_PORT=587
MAIL_USERNAME=apikey
MAIL_PASSWORD=SG.<your-key>
MAIL_FROM=noreply@yourregistry.de
```

## E-Mail-Vorlagen { #email-templates }

Vorlagen sind Thymeleaf HTML-Dateien in:
```
backend/src/main/resources/infrastructure/email/templates/
├── welcome.html
├── onboarding-invite.html
├── kyc-approved.html
├── kyc-rejected.html
└── kyc-expiring.html
```

Passen Sie diese Vorlagen mit Ihrem Branding an. Verfügbare Variablen:
- `${entityName}` – Name der juristischen Person
- `${onboardingUrl}` – Onboarding-Link (nur auf Einladung)
- `${registryUrl}` – Kunden-Frontend-Basis-URL

## Lokal testen { #testing-locally }

Verwenden Sie für die lokale Entwicklung [Greenmail](https://greenmail-mail-test.github.io/greenmail/) (in den Testabhängigkeiten enthalten) oder [Mailhog](https://github.com/mailhog/MailHog):

```bash
docker run -p 8025:8025 -p 1025:1025 mailhog/mailhog
```

Dann einstellen:
```dotenv
MAIL_HOST=localhost
MAIL_PORT=1025
```

E-Mails unter `http://localhost:8025` anzeigen.
