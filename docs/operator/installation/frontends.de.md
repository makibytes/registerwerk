---
title: Frontend-Setup
---

# Frontend-Setup

Beide Frontends sind eigenständige Angular-21-Anwendungen mit zonenloser Change Detection.

## Operator-Frontend (Port 4200)

```bash
cd frontend-operator
npm install
npm start           # Development
npm run build       # Production build → dist/frontend-operator/
```

`src/environments/environment.ts` konfigurieren:
```typescript
export const environment = {
  apiUrl: 'https://your-api-gateway-or-backend/api/v1'
};
```

## Kunden-Frontend (Port 4201)

```bash
cd frontend-customer
npm install
npm start           # Development (port 4201)
npm run build       # Production build → dist/frontend-customer/
```

## Bereitstellung in der Produktion

Beide Frontends sind statische SPA-Builds. Bereitstellung mit nginx:

```nginx
server {
    listen 80;
    root /var/www/frontend-operator;
    index index.html;
    location / { try_files $uri $uri/ /index.html; }
}
```

Oder verwenden Sie das mitgelieferte Dockerfile im jeweiligen Frontend-Verzeichnis.

## Zonenlose Change Detection

Beide Apps verwenden `provideZonelessChangeDetection()` anstelle von Zone.js. Das bedeutet:
- Kein `zone.js` in `polyfills` (das Array ist leer)
- Change Detection wird explizit durch Angular Signals und `markForCheck()` ausgelöst
- Kleinere Bundle-Größe, bessere Performance
