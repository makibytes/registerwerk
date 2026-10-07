import { HttpErrorResponse, HttpInterceptorFn, HttpStatusCode } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, tap, throwError } from 'rxjs';
import { MatSnackBar } from '@angular/material/snack-bar';
import { AuthService } from '../auth/auth.service';
import { clearAuthRedirectCooldown, requestAuthRedirect } from '../auth/redirect-guard';

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
 * 401s on these endpoints are an answer to the caller, not an expired session: a wrong password
 * on login, a spent impersonation code, or the boot-time "am I signed in?" probe. They are
 * rethrown untouched so the caller can show its inline message; redirecting would toast
 * "session could not be authenticated" and burn the one-shot redirect guard (8B-09).
 */
const AUTH_PROBE_SUFFIXES = ['/public/auth/login', '/public/auth/impersonate', '/auth/session'];

function isAuthProbe(url: string): boolean {
  const path = url.split(/[?#]/)[0];
  return AUTH_PROBE_SUFFIXES.some(suffix => path.endsWith(suffix));
}

/**
 * Global HTTP error handler:
 *  401 with a claims challenge → re-authenticates for the required auth context
 *  401 otherwise               → clears auth and redirects to /login
 *  403 PASSWORD_CHANGE_REQUIRED → routes to /change-password (no toast)
 *  403 → shows "Access denied" toast
 *  5xx → shows generic error toast
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const snackBar = inject(MatSnackBar);
  const auth = inject(AuthService);
  const router = inject(Router);

  return next(req).pipe(
    // Any successful response proves the session is good, so a later genuine expiry is not
    // mistaken for the redirect loop the cooldown exists to break.
    tap(() => clearAuthRedirectCooldown()),
    catchError(err => {
      switch (err.status) {
        case HttpStatusCode.Unauthorized: {
          if (isAuthProbe(req.url)) break;
          // A 401 carrying an OAuth2 claims challenge is not a rejected session — it means the
          // action needs a Conditional Access authentication context the current token lacks.
          // Signing the user out here would turn every step-up into an apparent logout.
          const claims = extractClaimsChallenge(err);
          if (claims) {
            // Both branches go through requestAuthRedirect because in Entra mode these are full
            // page navigations: without it, parallel 401s each start one, and a token Entra keeps
            // reissuing but the backend keeps rejecting produces an endless reload loop.
            if (!requestAuthRedirect(() => auth.acquireTokenWithClaims(claims))) {
              snackBar.open(
                'Additional verification was requested but could not be completed. Please sign in again.',
                'Dismiss',
                { duration: 8000, panelClass: 'snack-error' }
              );
            }
            break;
          }
          if (!requestAuthRedirect(() => { auth.clearToken(); auth.login(); })) {
            snackBar.open(
              'Your session could not be authenticated. Please sign in again.',
              'Dismiss',
              { duration: 8000, panelClass: 'snack-error' }
            );
          }
          break;
        }

        case HttpStatusCode.Forbidden: {
          const code = (err.error as { code?: string } | null)?.code;
          if (code === 'PASSWORD_CHANGE_REQUIRED') {
            // Not a permission failure: the account must set a new password first. Route there once
            // instead of toasting "access denied" for every request the page fires.
            auth.markPasswordChangeRequired();
            if (!router.url.startsWith('/change-password')) {
              void router.navigate(['/change-password']);
            }
            break;
          }
          if (code === 'IMPERSONATION_READ_ONLY' || code === 'IMPERSONATION_ACTION_DENIED') {
            snackBar.open(
              code === 'IMPERSONATION_READ_ONLY'
                ? 'This is a read-only support session. Changes are blocked.'
                : 'This action is not allowed during an impersonation session.',
              'Dismiss',
              { duration: 6000, panelClass: 'snack-error' }
            );
            break;
          }
          snackBar.open(
            'Access denied. You do not have permission to perform this action.',
            'Dismiss',
            { duration: 5000, panelClass: 'snack-error' }
          );
          break;
        }

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
