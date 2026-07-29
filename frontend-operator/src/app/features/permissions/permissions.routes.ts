import { Routes } from '@angular/router';

export const PERMISSION_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./permission-list/permission-list.component').then(
        (m) => m.PermissionListComponent
      ),
  },
  {
    path: ':id',
    loadComponent: () =>
      import('./permission-detail/permission-detail.component').then(
        (m) => m.PermissionDetailComponent
      ),
  },
];
