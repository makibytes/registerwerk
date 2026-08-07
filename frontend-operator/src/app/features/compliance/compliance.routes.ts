import { Routes } from '@angular/router';

export const COMPLIANCE_ROUTES: Routes = [
  {
    path: 'screening',
    loadComponent: () =>
      import('./screening/screening-queue.component').then(
        (m) => m.ScreeningQueueComponent,
      ),
  },
  {
    path: 'screening/runs/:runId',
    loadComponent: () =>
      import('./screening/screening-run-detail.component').then(
        (m) => m.ScreeningRunDetailComponent,
      ),
  },
  {
    path: 'chain-drift',
    loadComponent: () =>
      import('./chain-drift/chain-drift-queue.component').then(
        (m) => m.ChainDriftQueueComponent,
      ),
  },
  {
    path: 'casp-register',
    loadComponent: () =>
      import('./casp-register/casp-register.component').then(
        (m) => m.CaspRegisterComponent,
      ),
  },
  {
    path: 'holder-blocks',
    loadComponent: () =>
      import('./holder-blocks/holder-blocks.component').then(
        (m) => m.HolderBlocksComponent,
      ),
  },
  {
    path: 'dora',
    loadComponent: () =>
      import('./dora/dora-dashboard.component').then(
        (m) => m.DoraDashboardComponent,
      ),
  },
  {
    path: 'access-reviews',
    loadComponent: () =>
      import('./access-reviews/access-review-list.component').then(
        (m) => m.AccessReviewListComponent,
      ),
  },
  {
    path: 'access-reviews/:id',
    loadComponent: () =>
      import('./access-reviews/access-review-detail.component').then(
        (m) => m.AccessReviewDetailComponent,
      ),
  },
  {
    path: 'reporting',
    loadComponent: () =>
      import('./regulatory-reporting/regulatory-reporting.component').then(
        (m) => m.RegulatoryReportingComponent,
      ),
  },
  {
    path: 'dsar',
    loadComponent: () =>
      import('./dsar/erasure-queue.component').then(
        (m) => m.ErasureQueueComponent,
      ),
  },
  {
    path: 'support-tickets',
    loadComponent: () =>
      import('./support-tickets/support-ticket-queue.component').then(
        (m) => m.SupportTicketQueueComponent,
      ),
  },
  {
    path: 'support-tickets/:id',
    loadComponent: () =>
      import('./support-tickets/support-ticket-detail.component').then(
        (m) => m.SupportTicketDetailComponent,
      ),
  },
  {
    path: 'token-admin-grants',
    loadComponent: () =>
      import('./token-admin-grants/token-admin-grant-list.component').then(
        (m) => m.TokenAdminGrantListComponent,
      ),
  },
  { path: '', redirectTo: 'screening', pathMatch: 'full' },
];
