import { HttpInterceptorFn, HttpStatusCode } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { MatSnackBar } from '@angular/material/snack-bar';
import { AuthService } from '../auth/auth.service';

/**
 * Global HTTP error handler:
 *  401 → clears auth and redirects to /login
 *  403 → shows "Access denied" toast
 *  5xx → shows generic error toast
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const snackBar = inject(MatSnackBar);
  const auth = inject(AuthService);

  return next(req).pipe(
    catchError(err => {
      switch (err.status) {
        case HttpStatusCode.Unauthorized:
          auth.clearToken();
          auth.login();
          break;

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
