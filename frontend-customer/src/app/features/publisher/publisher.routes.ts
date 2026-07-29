import { Routes } from '@angular/router';

export const PUBLISHER_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./my-dapps/my-dapps.component').then((m) => m.MyDappsComponent),
  },
  {
    path: ':listingId/publish',
    loadComponent: () =>
      import('./publish-wizard/publish-wizard.component').then(
        (m) => m.PublishWizardComponent
      ),
  },
];
