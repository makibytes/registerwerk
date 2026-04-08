import { Routes } from '@angular/router';

export const ONBOARDING_ROUTES: Routes = [
  { path: '', redirectTo: 'create', pathMatch: 'full' },
  {
    path: 'create',
    loadComponent: () =>
      import('./create-entity/create-entity.component').then(
        (m) => m.CreateEntityComponent
      ),
  },
  {
    path: 'token/:entityId',
    loadComponent: () =>
      import('./token-generator/token-generator.component').then(
        (m) => m.TokenGeneratorComponent
      ),
  },
];
