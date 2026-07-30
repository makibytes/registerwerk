import { HttpErrorResponse, HttpInterceptorFn, HttpStatusCode } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { MatSnackBar } from '@angular/material/snack-bar';
import { AuthService } from '../auth/auth.service';

/**
 * Reads an OAuth2 claims challenge out of a 401.
 *
 * The header is authoritative, but browsers hide response headers from JavaScript unless every
 * hop exposes them (Kong's CORS `exposed_headers`, nginx, any future proxy). The backend
 * therefore repeats the challenge in the body, and we fall back to it — a stripped header would
 * otherwise present as an inexplicable logout loop.
 */
function extractClaimsChallenge(err: HttpErrorResponse): string | null {
  const header = err.headers?.get('WWW-Authenticate');
  const fromHeader = header?.match(/claims="([^"]+)"/)?.[1];
  if (fromHeader) return fromHeader;

  const body = err.error as { error?: string; claims?: string } | null;
  return body?.error === 'insufficient_claims' && body.claims ? body.claims : null;
}

/**
 * Global HTTP error handler:
 *  401 with a claims challenge → re-authenticates for the required auth context
 *  401 otherwise               → clears auth and redirects to /login
 *  403 → shows "Access denied" toast
 *  5xx → shows generic error toast
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const snackBar = inject(MatSnackBar);
  const auth = inject(AuthService);

  return next(req).pipe(
    catchError(err => {
      switch (err.status) {
        case HttpStatusCode.Unauthorized: {
          // A 401 carrying an OAuth2 claims challenge is not a rejected session — it means the
          // action needs a Conditional Access authentication context the current token lacks.
          // Signing the user out here would turn every step-up into an apparent logout.
          const claims = extractClaimsChallenge(err);
          if (claims) {
            auth.acquireTokenWithClaims(claims);
            break;
          }
          auth.clearToken();
          auth.login();
          break;
        }

        case HttpStatusCode.Forbidden:
          snackBar.open(
            'Access denied. You do not have permission to perform this action.',
            'Dismiss',
            { duration: 5000, panelClass: 'snack-error' }
          );
          break;

        case HttpStatusCode.UnprocessableEntity:
          // Validation errors — let individual components handle these
          break;

        default:
          if (err.status >= 500) {
            snackBar.open(
              'A server error occurred. Please try again later.',
              'Dismiss',
              { duration: 6000, panelClass: 'snack-error' }
            );
          }
      }

      return throwError(() => err);
    })
  );
};
