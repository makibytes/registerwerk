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
    path: 'reporting',
    loadComponent: () =>
      import('./regulatory-reporting/regulatory-reporting.component').then(
        (m) => m.RegulatoryReportingComponent,
      ),
  },
  { path: '', redirectTo: 'screening', pathMatch: 'full' },
];
