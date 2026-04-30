import { Routes } from '@angular/router';

export const USERS_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./user-list.component').then((m) => m.UserListComponent),
  },
];
