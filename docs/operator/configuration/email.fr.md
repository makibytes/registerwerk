---
title: Configuration des e-mails
---

# Configuration des e-mails

Le backend envoie des e-mails pour :
- Invitations d'intégration des clients (avec le lien de jeton unique)
- Notifications d'état KYC (approuvées, rejetées, expirantes)
- E-mails de bienvenue lors de l'activation de l'entité

## Configuration SMTP

```dotenv
MAIL_HOST=smtp.sendgrid.net
MAIL_PORT=587
MAIL_USERNAME=apikey
MAIL_PASSWORD=SG.<your-key>
MAIL_FROM=noreply@yourregistry.de
```

## Modèles d'e-mail

Les modèles sont des fichiers Thymeleaf HTML dans :
```
backend/src/main/resources/infrastructure/email/templates/
├── welcome.html
├── onboarding-invite.html
├── kyc-approved.html
├── kyc-rejected.html
└── kyc-expiring.html
```

Personnalisez ces modèles avec votre marque. Variables disponibles :
- `${entityName}` — nom de l'entité juridique
- `${onboardingUrl}` — lien d'intégration (sur invitation uniquement)
- `${registryUrl}` — URL de base de l'interface client

## Test local

Pour le développement local, utilisez [Greenmail](https://greenmail-mail-test.github.io/greenmail/) (inclus dans les dépendances de test) ou [Mailhog](https://github.com/mailhog/MailHog):

```bash
docker run -p 8025:8025 -p 1025:1025 mailhog/mailhog
```

Puis définissez :
```dotenv
MAIL_HOST=localhost
MAIL_PORT=1025
```

Voir les e-mails à `http://localhost:8025`.
