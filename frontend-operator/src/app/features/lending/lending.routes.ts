import { Routes } from '@angular/router';

export const LENDING_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./lending-admin.component').then((m) => m.LendingAdminComponent),
  },
];
