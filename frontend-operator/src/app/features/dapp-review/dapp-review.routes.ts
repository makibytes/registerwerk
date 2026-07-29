import { Routes } from '@angular/router';

export const DAPP_REVIEW_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./review-queue/review-queue.component').then(
        (m) => m.ReviewQueueComponent
      ),
  },
  {
    path: ':versionId',
    loadComponent: () =>
      import('./review-detail/review-detail.component').then(
        (m) => m.ReviewDetailComponent
      ),
  },
];
