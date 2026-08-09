---
title: Configuración del frontend
---

# Configuración del frontend { #frontend-setup }

Ambos frontends son aplicaciones independientes de Angular 22 con detección de cambios sin zona nativa.

## Frontend del operador (puerto 4200) { #operator-frontend-port-4200 }

```bash
cd frontend-operator
npm install
npm start           # Development
npm run build       # Production build → dist/frontend-operator/
```

Configurar `src/environments/environment.ts`:
```typescript
export const environment = {
  apiUrl: 'https://your-api-gateway-or-backend/api/v1'
};
```

## Frontend del cliente (puerto 4201) { #customer-frontend-port-4201 }

```bash
cd frontend-customer
npm install
npm start           # Development (port 4201)
npm run build       # Production build → dist/frontend-customer/
```

## Sirviendo en producción { #serving-in-production }

Ambas interfaces son compilaciones estáticas SPA. Servir con nginx:

```nginx
server {
    listen 80;
    root /var/www/frontend-operator;
    index index.html;
    location / { try_files $uri $uri/ /index.html; }
}
```

O use el Dockerfile incluido en cada directorio de frontend.

## Detección de cambios sin zona { #zoneless-change-detection }

Angular 22 ejecuta ambas aplicaciones sin zonas de forma predeterminada; no se requiere un proveedor explícito. Esto significa:
- No hay `zone.js` en `polyfills` (la matriz está vacía)
- La detección de cambios se activa explícitamente mediante señales (signals) de Angular y `markForCheck()`
- Tamaño de paquete más pequeño, mejor rendimiento
