import { Routes } from '@angular/router';

export const REPO_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./repo-disputes.component').then((m) => m.RepoDisputesComponent),
  },
];
