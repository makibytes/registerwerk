import { Routes } from '@angular/router';

export const PAYMENT_RAIL_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./rail-list/rail-list.component').then(
        (m) => m.RailListComponent
      ),
  },
];
