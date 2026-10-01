import { Routes } from '@angular/router';

export const TRADING_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./unresolved-trades.component').then((m) => m.UnresolvedTradesComponent),
  },
];
