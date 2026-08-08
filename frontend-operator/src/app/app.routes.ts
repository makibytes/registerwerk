import { Routes } from '@angular/router';
import { authGuard, roleGuard } from './core/auth/auth.guard';
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
        canActivate: [roleGuard],
        data: { roles: ['REGISTRY_ADMIN', 'AUDIT'] },
        loadChildren: () =>
          import('./features/customers/customers.routes').then(
            (m) => m.CUSTOMER_ROUTES
          ),
      },
      {
        path: 'my-clients',
        canActivate: [roleGuard],
        data: { roles: ['RELATIONSHIP_MANAGER'] },
        loadComponent: () =>
          import('./features/my-clients/my-clients.component').then(
            (m) => m.MyClientsComponent
          ),
      },
      {
        path: 'users',
        canActivate: [roleGuard],
        data: { roles: ['REGISTRY_ADMIN'] },
        loadChildren: () =>
          import('./features/users/users.routes').then((m) => m.USERS_ROUTES),
      },
      {
        path: 'registry',
        canActivate: [roleGuard],
        data: { roles: ['REGISTRY_ADMIN', 'AUDIT'] },
        loadComponent: () =>
          import('./features/customers/registry-overview/registry-overview.component').then(
            (m) => m.RegistryOverviewComponent
          ),
      },
      {
        path: 'onboarding',
        canActivate: [roleGuard],
        data: { roles: ['REGISTRY_ADMIN'] },
        loadChildren: () =>
          import('./features/onboarding/onboarding.routes').then(
            (m) => m.ONBOARDING_ROUTES
          ),
      },
      {
        path: 'assets',
        canActivate: [roleGuard],
        data: { roles: ['REGISTRY_ADMIN', 'AUDIT'] },
        loadChildren: () =>
          import('./features/assets/assets.routes').then(
            (m) => m.ASSET_ROUTES
          ),
      },
      {
        path: 'audit',
        canActivate: [roleGuard],
        data: { roles: ['REGISTRY_ADMIN', 'AUDIT'] },
        loadComponent: () =>
          import('./features/audit/audit-log.component').then(
            (m) => m.AuditLogComponent
          ),
      },
      {
        path: 'compliance',
        canActivate: [roleGuard],
        data: { roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT'] },
        loadChildren: () =>
          import('./features/compliance/compliance.routes').then(
            (m) => m.COMPLIANCE_ROUTES
          ),
      },
      {
        path: 'organizations',
        canActivate: [roleGuard],
        data: { roles: ['REGISTRY_ADMIN'] },
        loadChildren: () =>
          import('./features/organizations/organizations.routes').then(
            (m) => m.ORGANIZATION_ROUTES
          ),
      },
      {
        path: 'permissions',
        canActivate: [roleGuard],
        data: { roles: ['REGISTRY_ADMIN'] },
        loadChildren: () =>
          import('./features/permissions/permissions.routes').then(
            (m) => m.PERMISSION_ROUTES
          ),
      },
      {
        path: 'payment-rails',
        canActivate: [roleGuard],
        data: { roles: ['REGISTRY_ADMIN'] },
        loadChildren: () =>
          import('./features/payment-rails/payment-rails.routes').then(
            (m) => m.PAYMENT_RAIL_ROUTES
          ),
      },
      {
        path: 'dapp-review',
        canActivate: [roleGuard],
        data: { roles: ['REGISTRY_ADMIN'] },
        loadChildren: () =>
          import('./features/dapp-review/dapp-review.routes').then(
            (m) => m.DAPP_REVIEW_ROUTES
          ),
      },
      {
        path: 'dapp-catalog',
        canActivate: [roleGuard],
        data: { roles: ['REGISTRY_ADMIN'] },
        loadComponent: () =>
          import('./features/dapp-catalog/dapp-catalog.component').then(
            (m) => m.DappCatalogComponent
          ),
      },
      {
        path: 'transactions',
        canActivate: [roleGuard],
        data: { roles: ['REGISTRY_ADMIN', 'AUDIT'] },
        loadComponent: () =>
          import('./features/transactions/transaction-console.component').then(
            (m) => m.TransactionConsoleComponent
          ),
      },
      {
        path: 'network-nodes',
        canActivate: [roleGuard],
        data: { roles: ['REGISTRY_ADMIN'] },
        loadComponent: () =>
          import('./features/network-nodes/network-nodes.component').then(
            (m) => m.NetworkNodesComponent
          ),
      },
      {
        path: 'wallets',
        canActivate: [roleGuard],
        data: { roles: ['REGISTRY_ADMIN'] },
        loadComponent: () =>
          import('./features/wallets/wallets-list.component').then(
            (m) => m.WalletsListComponent
          ),
      },
      {
        path: 'wallets/:id',
        canActivate: [roleGuard],
        data: { roles: ['REGISTRY_ADMIN'] },
        loadComponent: () =>
          import('./features/wallets/wallet-detail.component').then(
            (m) => m.WalletDetailComponent
          ),
      },
      {
        path: 'endpoints',
        canActivate: [roleGuard],
        data: { roles: ['REGISTRY_ADMIN'] },
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
