import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Factory guard — roleGuard(['ISSUER', 'REGISTRY_ADMIN'])
 *
 * Returns true when the authenticated user has at least one of the required roles.
 * Redirects to /dashboard (403-equivalent) when access is denied.
 */
export function roleGuard(requiredRoles: string[]): CanActivateFn {
  return (_route, _state) => {
    const auth = inject(AuthService);
    const router = inject(Router);

    if (!auth.isAuthenticatedSync()) {
      return router.createUrlTree(['/login']);
    }

    const userRoles = auth.getUserRoles();
    const hasAccess = requiredRoles.some(role => userRoles.includes(role));

    if (hasAccess) return true;

    // Insufficient role — redirect to dashboard with a notice
    return router.createUrlTree(['/dashboard']);
  };
}
