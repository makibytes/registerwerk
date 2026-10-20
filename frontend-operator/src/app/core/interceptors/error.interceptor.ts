import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, throwError } from 'rxjs';
import { SUPPRESS_ERROR_TOAST } from '@registerwerk/ui';
import { AuthService } from '../auth/auth.service';

/**
 * Global HTTP error handler. 401 clears the session and returns to login; 403 `PASSWORD_CHANGE_REQUIRED`
 * routes to the change-password screen; any other 403 and a failed connection show a toast. The 403 toast is
 * skipped for requests carrying {@link SUPPRESS_ERROR_TOAST}: those callers show the specific reason inline
 * (a wrong TOTP code is a 403 too, and "Access denied" over "The code is not valid" is wrong). An unreachable
 * server is always toasted: the callers have no inline text for it.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const authService = inject(AuthService);
  const snackBar = inject(MatSnackBar);
  const callerReportsErrors = req.context.get(SUPPRESS_ERROR_TOAST);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401) {
        authService.clearSession();
        // Invalid credentials are already handled by the login form. For all other requests,
        // return to login without issuing a second logout request from inside the interceptor.
        if (!req.url.includes('/public/auth/login')) {
          void router.navigate(['/login']);
        }
      } else if (error.status === 403 && (error.error as { code?: string } | null)?.code === 'PASSWORD_CHANGE_REQUIRED') {
        // Not a permission failure: the account must set a new password first. Route to that screen
        // (once) instead of toasting "access denied" for every request the page fires.
        authService.markPasswordChangeRequired();
        if (!router.url.startsWith('/change-password')) {
          void router.navigate(['/change-password']);
        }
      } else if (error.status === 403 && !callerReportsErrors) {
        snackBar.open(
          'Access denied. You do not have permission to perform this action.',
          'Dismiss',
          { duration: 5000, panelClass: 'snack-error' },
        );
      } else if (error.status === 0) {
        snackBar.open(
          'The server could not be reached. Check your connection and try again.',
          'Dismiss',
          { duration: 6000, panelClass: 'snack-error' },
        );
      }

      return throwError(() => error);
    })
  );
};
