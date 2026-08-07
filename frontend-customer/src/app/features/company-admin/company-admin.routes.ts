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
  {
    path: 'external-ids',
    loadComponent: () => import('./external-ids/external-id-admin.component').then(m => m.ExternalIdAdminComponent)
  },
  {
    path: 'org-identity',
    loadComponent: () => import('./org-identity/org-identity.component').then(m => m.OrgIdentityComponent)
  },
  {
    path: 'beneficial-owners',
    loadComponent: () => import('./beneficial-owners/beneficial-owners.component').then(m => m.BeneficialOwnersComponent)
  },
];
