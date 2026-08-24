---
title: Configurazione del frontend
---

# Configurazione frontend { #frontend-setup }

Entrambi i frontend sono applicazioni Angular 22 autonome con rilevamento delle modifiche zoneless nativo.

## Frontend operatore (porta 4200) { #operator-frontend-port-4200 }

```bash
cd frontend-operator
npm install
npm start           # Development
npm run build       # Production build → dist/frontend-operator/
```

Configura `src/environments/environment.ts`:
```typescript
export const environment = {
  apiUrl: 'https://your-api-gateway-or-backend/api/v1'
};
```

## Frontend cliente (porta 4201) { #customer-frontend-port-4201 }

```bash
cd frontend-customer
npm install
npm start           # Development (port 4201)
npm run build       # Production build → dist/frontend-customer/
```

## Servito in produzione { #serving-in-production }

Entrambi i frontend sono build SPA statiche. Servire con nginx:

```nginx
server {
    listen 80;
    root /var/www/frontend-operator;
    index index.html;
    location / { try_files $uri $uri/ /index.html; }
}
```

Oppure utilizza il Dockerfile incluso in ciascuna directory frontend.

## Rilevamento modifiche senza zone { #zoneless-change-detection }

Angular 22 esegue entrambe le app senza zone per impostazione predefinita; non è richiesto un provider esplicito. Ciò significa:
- Nessun `zone.js` in `polyfills` (l'array è vuoto)
- Il rilevamento delle modifiche viene attivato esplicitamente dai signal di Angular e da `markForCheck()`
- Dimensioni del bundle più piccole, prestazioni migliori
