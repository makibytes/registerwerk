import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { switchMap } from 'rxjs';
import { TokenSource } from '../auth/token-source';

/**
 * Attaches the bearer token to outgoing requests.
 *
 * Two things it deliberately does:
 *
 * - **Leaves an explicit `Authorization` header alone.** Step-up calls send a short-lived
 *   step-up token in place of the session token; overwriting it would silently defeat the
 *   step-up check. (The operator app's interceptor has always done this; this one did not.)
 * - **Asks the {@link TokenSource} for a token rather than reading storage.** Under Entra the
 *   token has to be silently renewed when it nears expiry, which a synchronous read cannot do.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.headers.has('Authorization')) {
    return next(req);
  }

  return inject(TokenSource)
    .acquireToken$()
    .pipe(
      switchMap(token =>
        next(token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req)
      )
    );
};
