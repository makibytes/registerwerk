import { Routes } from '@angular/router';

export const ORGANIZATION_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./organization-list/organization-list.component').then(
        (m) => m.OrganizationListComponent
      ),
  },
  {
    path: ':id',
    loadComponent: () =>
      import('./organization-detail/organization-detail.component').then(
        (m) => m.OrganizationDetailComponent
      ),
  },
];
