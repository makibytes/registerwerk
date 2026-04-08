import { Routes } from '@angular/router';

export const CUSTOMER_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./customer-list/customer-list.component').then(
        (m) => m.CustomerListComponent
      ),
  },
  {
    path: ':id',
    loadComponent: () =>
      import('./customer-detail/customer-detail.component').then(
        (m) => m.CustomerDetailComponent
      ),
  },
];
