import { Routes } from '@angular/router';

export const INVESTMENT_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./investment-list/investment-list.component').then(m => m.InvestmentListComponent)
  },
  {
    path: ':holderId',
    loadComponent: () => import('./investment-detail/investment-detail.component').then(m => m.InvestmentDetailComponent)
  },
];
