---
id: email
title: Email Configuration
sidebar_position: 5
---

# Email Configuration

The backend sends emails for:
- Customer onboarding invitations (with the one-time token link)
- KYC status notifications (approved, rejected, expiring)
- Welcome emails on entity activation

## SMTP configuration

```dotenv
MAIL_HOST=smtp.sendgrid.net
MAIL_PORT=587
MAIL_USERNAME=apikey
MAIL_PASSWORD=SG.<your-key>
MAIL_FROM=noreply@yourregistry.de
```

## Email templates

Templates are Thymeleaf HTML files in:
```
backend/src/main/resources/infrastructure/email/templates/
├── welcome.html
├── onboarding-invite.html
├── kyc-approved.html
├── kyc-rejected.html
└── kyc-expiring.html
```

Customize these templates with your branding. Variables available:
- `${entityName}` — legal entity name
- `${onboardingUrl}` — onboarding link (invite only)
- `${registryUrl}` — customer frontend base URL

## Testing locally

For local development, use [Greenmail](https://greenmail-mail-test.github.io/greenmail/) (included in test dependencies) or [Mailhog](https://github.com/mailhog/MailHog):

```bash
docker run -p 8025:8025 -p 1025:1025 mailhog/mailhog
```

Then set:
```dotenv
MAIL_HOST=localhost
MAIL_PORT=1025
```

View emails at `http://localhost:8025`.
