import { Routes } from '@angular/router';

export const ASSET_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./asset-list/asset-list.component').then(
        (m) => m.AssetListComponent
      ),
  },
  {
    path: ':id',
    loadComponent: () =>
      import('./asset-detail/asset-detail.component').then(
        (m) => m.AssetDetailComponent
      ),
  },
  {
    path: ':id/edit',
    loadComponent: () =>
      import('./asset-edit/asset-edit.component').then(
        (m) => m.AssetEditComponent
      ),
  },
];
