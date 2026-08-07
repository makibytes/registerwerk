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
        path: 'my-clients',
        loadComponent: () =>
          import('./features/my-clients/my-clients.component').then(
            (m) => m.MyClientsComponent
          ),
      },
      {
        path: 'users',
        loadChildren: () =>
          import('./features/users/users.routes').then((m) => m.USERS_ROUTES),
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
        path: 'compliance',
        loadChildren: () =>
          import('./features/compliance/compliance.routes').then(
            (m) => m.COMPLIANCE_ROUTES
          ),
      },
      {
        path: 'organizations',
        loadChildren: () =>
          import('./features/organizations/organizations.routes').then(
            (m) => m.ORGANIZATION_ROUTES
          ),
      },
      {
        path: 'permissions',
        loadChildren: () =>
          import('./features/permissions/permissions.routes').then(
            (m) => m.PERMISSION_ROUTES
          ),
      },
      {
        path: 'payment-rails',
        loadChildren: () =>
          import('./features/payment-rails/payment-rails.routes').then(
            (m) => m.PAYMENT_RAIL_ROUTES
          ),
      },
      {
        path: 'dapp-review',
        loadChildren: () =>
          import('./features/dapp-review/dapp-review.routes').then(
            (m) => m.DAPP_REVIEW_ROUTES
          ),
      },
      {
        path: 'dapp-catalog',
        loadComponent: () =>
          import('./features/dapp-catalog/dapp-catalog.component').then(
            (m) => m.DappCatalogComponent
          ),
      },
      {
        path: 'transactions',
        loadComponent: () =>
          import('./features/transactions/transaction-console.component').then(
            (m) => m.TransactionConsoleComponent
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
      {
        path: 'wallets/:id',
        loadComponent: () =>
          import('./features/wallets/wallet-detail.component').then(
            (m) => m.WalletDetailComponent
          ),
      },
      {
        path: 'endpoints',
        loadComponent: () =>
          import('./features/endpoints/endpoints-list.component').then(
            (m) => m.EndpointsListComponent
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
