import { Routes } from '@angular/router';

export const ISSUANCE_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./issuance-list/issuance-list.component').then(m => m.IssuanceListComponent)
  },
  {
    path: 'new',
    loadComponent: () => import('./issuance-wizard/issuance-wizard.component').then(m => m.IssuanceWizardComponent)
  },
  {
    path: ':id',
    loadComponent: () => import('./issuance-detail/issuance-detail.component').then(m => m.IssuanceDetailComponent)
  },
];
