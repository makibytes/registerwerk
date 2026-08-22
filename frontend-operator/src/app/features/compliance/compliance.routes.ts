import { Routes } from '@angular/router';
import { roleGuard } from '../../core/auth/auth.guard';

export const COMPLIANCE_ROUTES: Routes = [
  {
    path: 'screening',
    canActivate: [roleGuard],
    data: { roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER'] },
    loadComponent: () =>
      import('./screening/screening-queue.component').then(
        (m) => m.ScreeningQueueComponent,
      ),
  },
  {
    path: 'screening/runs/:runId',
    canActivate: [roleGuard],
    data: { roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER'] },
    loadComponent: () =>
      import('./screening/screening-run-detail.component').then(
        (m) => m.ScreeningRunDetailComponent,
      ),
  },
  {
    path: 'chain-drift',
    canActivate: [roleGuard],
    data: { roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT'] },
    loadComponent: () =>
      import('./chain-drift/chain-drift-queue.component').then(
        (m) => m.ChainDriftQueueComponent,
      ),
  },
  {
    path: 'casp-register',
    canActivate: [roleGuard],
    data: { roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER'] },
    loadComponent: () =>
      import('./casp-register/casp-register.component').then(
        (m) => m.CaspRegisterComponent,
      ),
  },
  {
    path: 'holder-blocks',
    canActivate: [roleGuard],
    data: { roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER'] },
    loadComponent: () =>
      import('./holder-blocks/holder-blocks.component').then(
        (m) => m.HolderBlocksComponent,
      ),
  },
  {
    path: 'dora',
    canActivate: [roleGuard],
    data: { roles: ['REGISTRY_ADMIN'] },
    loadComponent: () =>
      import('./dora/dora-dashboard.component').then(
        (m) => m.DoraDashboardComponent,
      ),
  },
  {
    path: 'access-reviews',
    canActivate: [roleGuard],
    data: { roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER'] },
    loadComponent: () =>
      import('./access-reviews/access-review-list.component').then(
        (m) => m.AccessReviewListComponent,
      ),
  },
  {
    path: 'access-reviews/:id',
    canActivate: [roleGuard],
    data: { roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER'] },
    loadComponent: () =>
      import('./access-reviews/access-review-detail.component').then(
        (m) => m.AccessReviewDetailComponent,
      ),
  },
  {
    path: 'reporting',
    canActivate: [roleGuard],
    data: { roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER'] },
    loadComponent: () =>
      import('./regulatory-reporting/regulatory-reporting.component').then(
        (m) => m.RegulatoryReportingComponent,
      ),
  },
  {
    path: 'dsar',
    canActivate: [roleGuard],
    data: { roles: ['REGISTRY_ADMIN'] },
    loadComponent: () =>
      import('./dsar/erasure-queue.component').then(
        (m) => m.ErasureQueueComponent,
      ),
  },
  {
    path: 'support-tickets',
    canActivate: [roleGuard],
    data: { roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT'] },
    loadComponent: () =>
      import('./support-tickets/support-ticket-queue.component').then(
        (m) => m.SupportTicketQueueComponent,
      ),
  },
  {
    path: 'support-tickets/:id',
    canActivate: [roleGuard],
    data: { roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT'] },
    loadComponent: () =>
      import('./support-tickets/support-ticket-detail.component').then(
        (m) => m.SupportTicketDetailComponent,
      ),
  },
  {
    path: 'token-admin-grants',
    canActivate: [roleGuard],
    data: { roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER'] },
    loadComponent: () =>
      import('./token-admin-grants/token-admin-grant-list.component').then(
        (m) => m.TokenAdminGrantListComponent,
      ),
  },
  {
    path: 'unresolved-compensation',
    canActivate: [roleGuard],
    data: { roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT'] },
    loadComponent: () =>
      import('./finality/unresolved-compensation-queue.component').then(
        (m) => m.UnresolvedCompensationQueueComponent,
      ),
  },
  {
    path: 'finality-policy',
    canActivate: [roleGuard],
    data: { roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER', 'AUDIT'] },
    loadComponent: () =>
      import('./finality/finality-policy.component').then(
        (m) => m.FinalityPolicyComponent,
      ),
  },
  { path: '', redirectTo: 'screening', pathMatch: 'full' },
];
