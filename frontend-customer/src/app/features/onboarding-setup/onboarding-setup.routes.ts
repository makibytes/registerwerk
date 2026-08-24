import { Routes } from '@angular/router';
import { authGuard } from '../../core/auth/auth.guard';
import { roleGuard } from '../../core/auth/role.guard';

export const ONBOARDING_SETUP_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./onboarding-entry/onboarding-entry.component').then(m => m.OnboardingEntryComponent)
  },
  {
    path: 'redeem/:token',
    loadComponent: () => import('./redeem-token/redeem-token.component').then(m => m.RedeemTokenComponent)
  },
  {
    path: 'setup-users',
    canActivate: [authGuard, roleGuard(['COMPANY_ADMIN', 'REGISTRY_ADMIN'])],
    loadComponent: () => import('./setup-users/setup-users.component').then(m => m.SetupUsersComponent)
  },
  {
    path: 'setup-idp',
    canActivate: [authGuard, roleGuard(['COMPANY_ADMIN', 'REGISTRY_ADMIN'])],
    loadComponent: () => import('./setup-idp/setup-idp.component').then(m => m.SetupIdpComponent)
  },
];
