import { Routes } from '@angular/router';

export const MARKETPLACE_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./marketplace-catalog/marketplace-catalog.component').then(
        (m) => m.MarketplaceCatalogComponent
      ),
  },
  {
    path: ':slug',
    loadComponent: () =>
      import('./marketplace-detail/marketplace-detail.component').then(
        (m) => m.MarketplaceDetailComponent
      ),
  },
];
