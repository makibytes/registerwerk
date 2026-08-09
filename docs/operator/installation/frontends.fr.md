---
title: Configuration du frontend
---

# Configuration du frontend

Les deux frontaux sont des applications autonomes Angular 22 avec détection de changement sans zone native.

## Frontend de l'opérateur (port 4200)

```bash
cd frontend-operator
npm install
npm start           # Development
npm run build       # Production build → dist/frontend-operator/
```

Configurer `src/environments/environment.ts` :
```typescript
export const environment = {
  apiUrl: 'https://your-api-gateway-or-backend/api/v1'
};
```

## Frontend client (port 4201)

```bash
cd frontend-customer
npm install
npm start           # Development (port 4201)
npm run build       # Production build → dist/frontend-customer/
```

## Servir en production

Les deux interfaces sont des versions statiques de SPA. Servir avec nginx :

```nginx
server {
    listen 80;
    root /var/www/frontend-operator;
    index index.html;
    location / { try_files $uri $uri/ /index.html; }
}
```

Ou utilisez le Dockerfile inclus dans chaque répertoire frontend.

## Détection de changement sans zone

Angular 22 exécute les deux applications sans zone par défaut ; aucun fournisseur explicite n'est requis. Cela signifie :
- Pas de `zone.js` dans `polyfills` (le tableau est vide)
- La détection des changements est déclenchée explicitement par les signaux Angular et `markForCheck()`
- Taille de paquet plus petite, meilleures performances
