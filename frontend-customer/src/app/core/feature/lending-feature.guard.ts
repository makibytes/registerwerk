import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { environment } from '../../../environments/environment';

/**
 * Build-time release gate for the unresolved repo/lending product. Hiding navigation is not a
 * security boundary, so every lazy-route match is denied as well. The backend independently
 * denies the feature by default; both gates must be explicitly enabled by a reviewed release.
 */
export const lendingFeatureGuard: CanMatchFn = () => {
  if (environment.lendingEnabled) return true;
  return inject(Router).createUrlTree(['/dashboard']);
};
