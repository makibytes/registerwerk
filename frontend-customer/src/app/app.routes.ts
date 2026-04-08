import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { roleGuard } from './core/auth/role.guard';
import { ShellComponent } from './layout/shell/shell.component';

export const routes: Routes = [
  // Public — onboarding (no auth required)
  {
    path: 'onboarding',
    loadChildren: () => import('./features/onboarding-setup/onboarding-setup.routes').then(m => m.ONBOARDING_SETUP_ROUTES)
  },
  // Authenticated shell
  {
    path: '',
    component: ShellComponent,
    canActivate: [authGuard],
    children: [
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
      {
        path: 'dashboard',
        loadComponent: () => import('./features/dashboard/dashboard.component').then(m => m.DashboardComponent)
      },
      {
        path: 'issuances',
        canActivate: [roleGuard(['ISSUER', 'REGISTRY_ADMIN'])],
        loadChildren: () => import('./features/issuances/issuances.routes').then(m => m.ISSUANCE_ROUTES)
      },
      {
        path: 'investments',
        canActivate: [roleGuard(['INVESTOR', 'REGISTRY_ADMIN'])],
        loadChildren: () => import('./features/investments/investments.routes').then(m => m.INVESTMENT_ROUTES)
      },
      {
        path: 'company-admin',
        canActivate: [roleGuard(['COMPANY_ADMIN'])],
        loadChildren: () => import('./features/company-admin/company-admin.routes').then(m => m.COMPANY_ADMIN_ROUTES)
      },
      {
        path: 'kyc',
        loadComponent: () => import('./features/kyc/kyc-status.component').then(m => m.KycStatusComponent)
      }
    ]
  },
  {
    path: 'login',
    loadComponent: () => import('./core/auth/login.component').then(m => m.LoginComponent)
  },
  { path: '**', redirectTo: '' }
];
