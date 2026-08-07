import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs/operators';
import { AuthService } from './auth.service';

/**
 * Async now — auth state used to be knowable synchronously from a decoded localStorage JWT;
 * now it comes from `GET /auth/session` (the token itself lives in an httpOnly cookie), so the
 * first guard check on a hard page load has to wait for that call before it can decide.
 * `ensureInitialized()` caches the result, so this is a no-op await on every check after the
 * first.
 */
export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);

  return authService.ensureInitialized().pipe(
    map(authenticated => authenticated ? true : router.createUrlTree(['/login']))
  );
};
