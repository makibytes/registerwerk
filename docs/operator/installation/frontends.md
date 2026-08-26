---
title: Frontend Setup
---

# Frontend Setup

Both frontends are Angular 22 standalone applications with native zoneless change detection.

## Operator frontend (port 44200)

```bash
cd frontend-operator
npm install
npm start           # Development
npm run build       # Production build → dist/frontend-operator/
```

Configure `src/environments/environment.ts`:
```typescript
export const environment = {
  apiUrl: 'https://your-api-gateway-or-backend/api/v1'
};
```

## Customer frontend (port 44201)

```bash
cd frontend-customer
npm install
npm start           # Development (port 44201)
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

Angular 22 runs both apps zoneless by default; no explicit provider is required. This means:
- No `zone.js` in `polyfills` (the array is empty)
- Change detection is triggered explicitly by Angular signals and `markForCheck()`
- Smaller bundle size, better performance
