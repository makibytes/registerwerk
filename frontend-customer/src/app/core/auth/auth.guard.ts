import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs/operators';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = (_route, _state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return auth.isAuthenticated().pipe(
    map(authenticated => {
      if (!authenticated) return router.createUrlTree(['/login']);
      // A must_change_password session can reach nothing but the change-password screen.
      return auth.isPasswordChangeRequired() ? router.createUrlTree(['/change-password']) : true;
    })
  );
};

/** Guard of the change-password screen itself: needs a session, but not an unrestricted one. */
export const changePasswordGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return auth.isAuthenticated().pipe(
    map(authenticated => authenticated ? true : router.createUrlTree(['/login']))
  );
};
