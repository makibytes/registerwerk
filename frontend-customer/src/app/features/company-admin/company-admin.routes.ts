import { Routes } from '@angular/router';

export const COMPANY_ADMIN_ROUTES: Routes = [
  { path: '', redirectTo: 'users', pathMatch: 'full' },
  {
    path: 'users',
    loadComponent: () => import('./user-management/user-list.component').then(m => m.UserListComponent)
  },
  {
    path: 'idp',
    loadComponent: () => import('./idp-settings/idp-settings.component').then(m => m.IdpSettingsComponent)
  },
];
