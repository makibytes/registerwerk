import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { PlatformCapabilitiesService } from './platform-capabilities';

/**
 * Runtime release gate for securities-backed lending. Hiding navigation is not a security
 * boundary, so every lazy-route match is denied as well; the backend independently enforces
 * the same release approval at each service boundary.
 */
export const lendingFeatureGuard: CanMatchFn = () => {
  if (inject(PlatformCapabilitiesService).lendingEnabled()) return true;
  return inject(Router).createUrlTree(['/dashboard']);
};
