---
id: frontends
title: Frontend Setup
sidebar_position: 4
---

# Frontend Setup

Both frontends are Angular 21 standalone applications with zoneless change detection.

## Operator frontend (port 4200)

```bash
cd frontend-operator
npm install
npm start           # Development
npm run build       # Production build → dist/frontend-operator/
```

Configure `src/environments/environment.ts`:
```typescript
export const environment = {
  apiUrl: 'https://your-kong-proxy.com',
  msalConfig: {
    auth: {
      clientId: '<entra-app-id>',
      authority: 'https://login.microsoftonline.com/<tenant>',
    }
  }
};
```

## Customer frontend (port 4201)

```bash
cd frontend-customer
npm install
npm start           # Development (port 4201)
npm run build       # Production build → dist/frontend-customer/
```

## Serving in production

Both frontends are static SPA builds. Serve with nginx:

```nginx
server {
    listen 80;
    root /var/www/frontend-operator;
    index index.html;
    location / { try_files $uri $uri/ /index.html; }
}
```

Or use the included Dockerfile in each frontend directory.

## Zoneless change detection

Both apps use `provideZonelessChangeDetection()` instead of Zone.js. This means:
- No `zone.js` in `polyfills` (the array is empty)
- Change detection is triggered explicitly by Angular signals and `markForCheck()`
- Smaller bundle size, better performance
