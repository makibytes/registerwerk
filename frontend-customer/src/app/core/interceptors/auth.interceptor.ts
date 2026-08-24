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
 * - **Always sends `withCredentials: true`.** Under `CookieTokenSource` the httpOnly
 *   `rw_session` cookie (and the `XSRF-TOKEN` cookie Angular reads for the CSRF header) only
 *   attach to same-site-eligible requests that opt in; under Entra this is a harmless no-op
 *   since the browser has no Registerwerk cookies to send.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.headers.has('Authorization')) {
    return next(req.clone({ withCredentials: true }));
  }

  return inject(TokenSource)
    .acquireToken$()
    .pipe(
      switchMap(token =>
        next(
          req.clone({
            withCredentials: true,
            ...(token ? { setHeaders: { Authorization: `Bearer ${token}` } } : {}),
          })
        )
      )
    );
};
