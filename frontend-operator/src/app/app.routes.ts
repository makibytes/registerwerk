import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';
import { ShellComponent } from './layout/shell/shell.component';

export const routes: Routes = [
  {
    path: '',
    component: ShellComponent,
    canActivate: [authGuard],
    children: [
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
      {
        path: 'dashboard',
        loadComponent: () =>
          import('./features/dashboard/dashboard.component').then(
            (m) => m.DashboardComponent
          ),
      },
      {
        path: 'customers',
        loadChildren: () =>
          import('./features/customers/customers.routes').then(
            (m) => m.CUSTOMER_ROUTES
          ),
      },
      {
        path: 'registry',
        loadComponent: () =>
          import('./features/customers/registry-overview/registry-overview.component').then(
            (m) => m.RegistryOverviewComponent
          ),
      },
      {
        path: 'onboarding',
        loadChildren: () =>
          import('./features/onboarding/onboarding.routes').then(
            (m) => m.ONBOARDING_ROUTES
          ),
      },
      {
        path: 'assets',
        loadChildren: () =>
          import('./features/assets/assets.routes').then(
            (m) => m.ASSET_ROUTES
          ),
      },
      {
        path: 'audit',
        loadComponent: () =>
          import('./features/audit/audit-log.component').then(
            (m) => m.AuditLogComponent
          ),
      },
      {
        path: 'network-nodes',
        loadComponent: () =>
          import('./features/network-nodes/network-nodes.component').then(
            (m) => m.NetworkNodesComponent
          ),
      },
      {
        path: 'wallets',
        loadComponent: () =>
          import('./features/wallets/wallets-list.component').then(
            (m) => m.WalletsListComponent
          ),
      },
    ],
  },
  {
    path: 'login',
    loadComponent: () =>
      import('./core/auth/login.component').then((m) => m.LoginComponent),
  },
  { path: '**', redirectTo: '' },
];
