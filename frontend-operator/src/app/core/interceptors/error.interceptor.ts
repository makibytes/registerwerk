import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const authService = inject(AuthService);
  const snackBar = inject(MatSnackBar);

  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401) {
        authService.clearSession();
        // Invalid credentials are already handled by the login form. For all other requests,
        // return to login without issuing a second logout request from inside the interceptor.
        if (!req.url.includes('/public/auth/login')) {
          void router.navigate(['/login']);
        }
      } else if (error.status === 403) {
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
