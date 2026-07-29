import { Routes } from '@angular/router';

export const LENDING_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./liquidity-overview/liquidity-overview.component').then((m) => m.LiquidityOverviewComponent),
  },
  {
    path: 'borrow/:holderId',
    loadComponent: () => import('./borrow-stepper/borrow-stepper.component').then((m) => m.BorrowStepperComponent),
  },
  {
    path: 'loans',
    loadComponent: () => import('./open-loans/open-loans.component').then((m) => m.OpenLoansComponent),
  },
  {
    path: 'supply',
    loadComponent: () => import('./supply-earn/supply-earn.component').then((m) => m.SupplyEarnComponent),
  },
];
